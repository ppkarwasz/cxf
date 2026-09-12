/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLResolver;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import org.apache.cxf.Bus;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.xmlschema.LSInputImpl;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.resource.URIResolver;

/**
 * A JAXP resolver that opts CXF's legitimate external references back in on a secure-by-default parser.
 * <p>
 * Apache Commons Secure XML denies every external reference a document names and offers a resolver as the sole
 * opt-in: the resolver is consulted before the securing floor and cannot be removed, a non-{@code null} return
 * allows that one reference, and a {@code null} return leaves it denied. The sense is therefore inverted from a
 * stock JAXP factory, where {@code null} hands the reference back to the parser to fetch.
 * </p>
 * <p>
 * One instance answers all four resolution channels JAXP defines, so the same object can be installed wherever
 * a factory takes a resolver, and a schema and the validator it produces can share one:
 * </p>
 * <pre>
 * XmlCatalogResolver resolver = new XmlCatalogResolver(bus);
 * schemaFactory.setResourceResolver(resolver);
 * documentBuilder.setEntityResolver(resolver);
 * transformerFactory.setURIResolver(resolver);
 * inputFactory.setXMLResolver(resolver);
 * </pre>
 * <p>
 * References are answered from configuration CXF already trusts, in order:
 * </p>
 * <ol>
 * <li>an OASIS catalog entry ({@link OASISCatalogManager}), which is integrator-authored and therefore honoured
 * even when it names a remote URI;</li>
 * <li>a local resource, resolved through {@link org.apache.cxf.resource.URIResolver} so that the classpath, the
 * file system and an enclosing archive are all searched;</li>
 * <li>nothing else &mdash; unless the caller asked for {@link Remote#ALLOWED}.</li>
 * </ol>
 * <p>
 * Every fetch runs through {@link org.apache.cxf.resource.URIResolver}, so the
 * {@code org.apache.cxf.resource.uriresolver.allowedSchemes} allowlist still applies.
 * </p>
 * <p>
 * Instances are stateless and may be shared between threads. The stream carried by a resolved resource is owned
 * by the parser that receives it, which closes it as it would any other input.
 * </p>
 *
 * @since 4.3.0
 */
public class XmlCatalogResolver
    implements EntityResolver, LSResourceResolver, javax.xml.transform.URIResolver, XMLResolver {

    /**
     * Whether a reference that neither the catalog nor a local resource answers may be fetched over the network.
     */
    public enum Remote {
        /**
         * Deny it. The correct choice wherever the identifier can come from an inbound message, since a remote
         * fetch of an attacker-named URI is an SSRF primitive.
         */
        DENIED,

        /**
         * Allow it, subject to the {@code org.apache.cxf.resource.uriresolver.allowedSchemes} allowlist. For
         * developer-trusted input only, such as the code-generation tools resolving an imported schema.
         */
        ALLOWED
    }

    private static final Logger LOG = LogUtils.getL7dLogger(XmlCatalogResolver.class);

    /** Bounds a single resolved resource, which is a schema, a DTD or a stylesheet rather than a payload. */
    private static final int MAX_RESOLVED_SIZE = 16 * 1024 * 1024;

    private final Bus bus;

    private final Remote remote;

    /**
     * Creates a resolver that denies remote references.
     *
     * @param bus the bus supplying the catalog; may be {@code null}
     */
    public XmlCatalogResolver(Bus bus) {
        this(bus, Remote.DENIED);
    }

    /**
     * Creates a resolver.
     *
     * @param bus    the bus supplying the catalog; may be {@code null}
     * @param remote whether an unmapped, non-local reference may be fetched
     */
    public XmlCatalogResolver(Bus bus, Remote remote) {
        this.bus = bus;
        this.remote = remote;
    }

    /**
     * Loads into the bus catalog every catalog on the classpath carrying the given name.
     * <p>
     * <strong>Every</strong> one is loaded, not merely the first: several jars may carry a catalog of the
     * same name, and taking whichever the class loader happens to return first makes resolution depend on
     * classpath order.
     * </p>
     *
     * @param classLoader the class loader to enumerate the catalogs with
     * @param catalogName their classpath name, such as {@code META-INF/jax-rs-catalog.xml}; a
     *                        {@code classpath:} scheme and a leading slash are tolerated
     * @throws IOException if a catalog with that name exists but cannot be read
     */
    public void loadCatalogs(ClassLoader classLoader, String catalogName) throws IOException {
        final OASISCatalogManager catalog = OASISCatalogManager.getCatalogManager(bus);
        if (catalog != null) {
            catalog.loadCatalogs(classLoader, stripClasspathScheme(catalogName));
        }
    }

    /**
     * Turns a catalog location into the resource name {@link ClassLoader#getResources} expects.
     *
     * @param catalogName the location, possibly written as a {@code classpath:} URI
     * @return the bare resource name
     */
    private static String stripClasspathScheme(String catalogName) {
        String name = catalogName;
        if (name.startsWith("classpath:")) {
            name = name.substring("classpath:".length());
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    /**
     * Answers DTDs, external entities and XInclude targets on a {@code DocumentBuilder} or an
     * {@code XMLReader}.
     *
     * {@inheritDoc}
     */
    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
        return resolve(publicId, systemId, null, null);
    }

    /**
     * Answers the schema path: the schema documents a schema pulls in ({@code xs:import}, {@code xs:include}
     * and {@code xsi:schemaLocation} hints) and the DTD and external entities of the instance document.
     *
     * {@inheritDoc}
     */
    @Override
    public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId,
                                   String baseURI) {
        final InputSource source = resolve(publicId, systemId, baseURI, namespaceURI);
        if (source == null) {
            return null;
        }
        final LSInput input = new LSInputImpl(source.getPublicId(), source.getSystemId(),
                                              source.getByteStream());
        input.setBaseURI(baseURI);
        return input;
    }

    /**
     * Answers the transform path: the stylesheet modules pulled in at compile time ({@code xsl:import},
     * {@code xsl:include}) and what the transform fetches as it runs ({@code document()} and, on XSLT 3.0,
     * {@code unparsed-text()}).
     *
     * {@inheritDoc}
     */
    @Override
    public Source resolve(String href, String base) {
        final InputSource source = resolve(null, href, base, null);
        return source == null ? null : new StreamSource(source.getByteStream(), source.getSystemId());
    }

    /**
     * Answers entities on the streaming path. StAX requires the resolver to open the resource itself and
     * accepts only an {@link InputStream}, an {@code XMLStreamReader} or an {@code XMLEventReader}.
     *
     * {@inheritDoc}
     */
    @Override
    public Object resolveEntity(String publicID, String systemID, String baseURI, String namespace) {
        final InputSource source = resolve(publicID, systemID, baseURI, null);
        return source == null ? null : source.getByteStream();
    }

    /**
     * Resolves one external reference, or answers {@code null} to leave it denied.
     *
     * @param publicId     the public identifier, or {@code null}
     * @param systemId     the system identifier the document named, or {@code null} for an {@code xs:import}
     *                         carrying no {@code schemaLocation}
     * @param baseURI      the base URI to resolve {@code systemId} against, or {@code null}
     * @param namespaceURI the namespace being imported, which only the schema path supplies; may be
     *                         {@code null}
     * @return the opened resource, or {@code null} if it is not one this bus answers
     */
    private InputSource resolve(String publicId, String systemId, String baseURI, String namespaceURI) {
        // A catalog entry is an integrator-authored configuration, so it opts the reference in on its own,
        // whatever it maps to. An import naming only a namespace carries no systemId to fall back on, so
        // the catalog is the only thing that can answer it.
        InputSource source = fromCatalog(publicId, systemId, namespaceURI);
        if (source == null && systemId != null
                && (URIResolver.isLocal(baseURI, systemId) || remote == Remote.ALLOWED)) {
            source = open(systemId, publicId, baseURI);
        }
        if (source == null && LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Not resolving external reference {0}",
                    systemId != null ? systemId : namespaceURI);
        }
        return source;
    }


    /**
     * Looks the identifier up in the bus catalog, as the document wrote it.
     * <p>
     * Resolving it against the base URI first is deliberately not done: neither {@link OASISCatalogManager}
     * nor the JDK's own catalog resolver does it, so it would only match entries no other catalog consumer
     * matches, and CXF's catalogs carry {@code rewriteSystem} entries whose start string is not absolute.
     * </p>
     *
     * @param publicId the public identifier, or {@code null}
     * @param systemId the system identifier as the document wrote it, or {@code null}
     * @param namespaceURI the namespace being imported, or {@code null}
     * @return the URI the catalog maps it to, or {@code null} if the catalog does not answer
     */
    private InputSource fromCatalog(String publicId, String systemId, String namespaceURI) {
        final OASISCatalogManager catalog = OASISCatalogManager.getCatalogManager(bus);
        if (catalog == null) {
            return null;
        }
        try {
            String resolved = null;
            if (systemId != null) {
                resolved = catalog.resolveSystem(systemId);
                if (resolved == null) {
                    resolved = catalog.resolveURI(systemId);
                }
            }
            if (resolved == null && publicId != null) {
                resolved = catalog.resolvePublic(publicId, systemId);
            }
            // Only an import that named no schemaLocation may be answered from its namespace. A schema
            // path always supplies one, and xs:include reports the *including* schema's target namespace
            // rather than null, so honouring the namespace here would resolve an uncatalogued include to
            // whatever the catalog maps the including schema to.
            if (resolved == null && systemId == null && namespaceURI != null) {
                resolved = catalog.resolveURI(namespaceURI);
                if (resolved == null) {
                    resolved = catalog.resolvePublic(namespaceURI, null);
                }
            }
            return resolved != null ? open(resolved, publicId, null) : null;
        } catch (IOException e) {
            LOG.log(Level.FINE, "Catalog lookup failed for " + systemId, e);
            return null;
        }
    }



    /**
     * Opens a URI through {@link org.apache.cxf.resource.URIResolver}, so the scheme allow-list applies.
     * <p>
     * The resolver is deliberately not closed: closing it would close the stream this method returns, which the
     * parser receiving it owns.
     * </p>
     *
     * @param uri      the URI to open
     * @param publicId the public identifier to report, or {@code null}
     * @param baseURI  the base URI to resolve against, or {@code null}
     * @return the opened resource, or {@code null} if it cannot be opened
     */
    private static InputSource open(String uri, String publicId, String baseURI) {
        try (URIResolver resolver = new URIResolver()) {
            resolver.resolve(baseURI, uri, XmlCatalogResolver.class);
            if (!resolver.isResolved()) {
                return null;
            }
            final InputStream stream = resolver.getInputStream();
            if (stream == null) {
                return null;
            }
            // Read it here rather than hand the resolver's own stream out: the resolver closes that stream
            // when this block ends, and a resource nothing goes on to read would otherwise never be closed
            // at all. The resources a catalog answers with are schemas, DTDs and stylesheets, all small.
            final InputSource source = new InputSource(
                new ByteArrayInputStream(IOUtils.readBytesFromStream(stream, MAX_RESOLVED_SIZE)));
            source.setPublicId(publicId);
            source.setSystemId(resolver.getURI() != null ? resolver.getURI().toString() : uri);
            return source;
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not open " + uri, e);
            return null;
        }
    }


}
