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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.stream.XMLResolver;
import javax.xml.transform.Source;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.extension.ExtensionManagerBus;
import org.apache.cxf.catalog.XmlCatalogResolver.Remote;
import org.apache.cxf.helpers.IOUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmlCatalogResolverTest {

    /** A classpath resource every local-resolution case resolves to. */
    private static final String LOCAL_SCHEMA = "schemas/echoSchema.xsd";

    /** The identifier {@code catalog/test-catalog.xml} maps onto {@link #LOCAL_SCHEMA}. */
    private static final String CATALOG_MAPPED = "http://cxf.apache.org/test/echo.xsd";

    private static final String REMOTE_BODY = "<remote/>";

    private Bus bus;
    private StubServer server;

    @Before
    public void setUp() throws Exception {
        bus = new ExtensionManagerBus();
        server = new StubServer();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (bus != null) {
            bus.shutdown(true);
        }
    }

    // --- the gate: what may be fetched over the network -------------------------------------------------

    @Test
    public void deniesRemoteReferenceByDefault() throws Exception {
        assertNull("an unmapped remote identifier must not be fetched",
                   new XmlCatalogResolver(bus).resolveEntity(null, server.url()));
        assertFalse("the denied host must never be contacted", server.wasContacted());
    }

    @Test
    public void fetchesRemoteReferenceWhenOptedIn() throws Exception {
        final InputSource source =
            new XmlCatalogResolver(bus, Remote.ALLOWED).resolveEntity(null, server.url());

        assertNotNull("Remote.ALLOWED must fetch what the catalog and the classpath do not answer", source);
        assertEquals(REMOTE_BODY, read(source));
        assertTrue("the opted-in host is contacted", server.wasContacted());
    }

    // --- what is resolved without opting in -------------------------------------------------------------

    @Test
    public void resolvesLocalClasspathResource() throws Exception {
        final InputSource source = new XmlCatalogResolver(bus).resolveEntity(null, LOCAL_SCHEMA);

        assertNotNull("a classpath resource is local and needs no opt-in", source);
        assertTrue(read(source).contains("schema"));
    }

    @Test
    public void resolvesCatalogMappedReferenceWithoutOptingIn() throws Exception {
        bus.getExtension(OASISCatalogManager.class)
            .loadCatalogs(Thread.currentThread().getContextClassLoader(), "catalog/test-catalog.xml");

        final InputSource source = new XmlCatalogResolver(bus).resolveEntity(null, CATALOG_MAPPED);

        assertNotNull("a catalog entry is integrator-authored configuration and opts the reference in", source);
        assertTrue(read(source).contains("schema"));
    }

    @Test
    public void resolvesRelativeReferenceAgainstBaseUri() throws Exception {
        final String base = getClass().getClassLoader().getResource(LOCAL_SCHEMA).toString();

        final LSInput input = new XmlCatalogResolver(bus)
            .resolveResource(null, null, null, "logSchema.xsd", base);

        assertNotNull("a sibling schema must resolve against the base URI", input);
        assertTrue(read(input.getByteStream()).contains("schema"));
    }

    @Test
    public void resolvesJarOverFile() throws Exception {
        final String jar = "jar:" + getClass().getResource("/org/apache/cxf/resource/resources/helloworld.bpr")
                           + "!/wsdl/hello_world.wsdl";

        assertNotNull("jar: wrapping a file: URL stays local",
                      new XmlCatalogResolver(bus).resolveEntity(null, jar));
    }

    @Test
    public void deniesJarOverHttp() throws Exception {
        assertNull("jar: wrapping a remote URL is a remote fetch and must be denied",
                   new XmlCatalogResolver(bus)
                       .resolveEntity(null, "jar:" + server.url() + "!/schema.xsd"));
        assertFalse("the denied host must never be contacted", server.wasContacted());
    }

    /**
     * Stock JDK ships no {@code wsjar} or {@code zip} protocol handler, so this case cannot reach the network
     * here whatever the classification is. It pins the intent for the containers that do ship one: {@code wsjar}
     * is WebSphere's spelling of {@code jar}, and a remote URL behind it must stay denied there too.
     */
    @Test
    public void deniesWsjarAndZipOverHttp() throws Exception {
        for (String scheme : new String[] {"wsjar", "zip"}) {
            assertNull(scheme + ": wrapping a remote URL must be denied",
                       new XmlCatalogResolver(bus)
                           .resolveEntity(null, scheme + ":" + server.url() + "!/schema.xsd"));
        }
        assertFalse("the denied host must never be contacted", server.wasContacted());
    }

    @Test
    public void deniesDeeplyNestedRemoteReference() throws Exception {
        assertNull("unwrapping must not be fooled by repeated nesting",
                   new XmlCatalogResolver(bus)
                       .resolveEntity(null, "jar:jar:jar:" + server.url() + "!/a!/b!/c.xsd"));
        assertFalse("the denied host must never be contacted", server.wasContacted());
    }

    @Test
    public void resolvesSchemaImportByNamespaceAlone() throws Exception {
        bus.getExtension(OASISCatalogManager.class)
            .loadCatalogs(Thread.currentThread().getContextClassLoader(), "catalog/test-catalog.xml");

        // xs:import may name a namespace and no schemaLocation, so systemId is null.
        final LSInput input = new XmlCatalogResolver(bus)
            .resolveResource(null, "http://cxf.apache.org/test/ns/log", null, null, null);

        assertNotNull("the catalog must answer an import that names only a namespace", input);
        assertTrue(read(input.getByteStream()).contains("schema"));
    }

    /**
     * An {@code xs:include} reports the <em>including</em> schema's target namespace, not {@code null}, and
     * always carries a schemaLocation. Answering it from the catalog entry for that namespace would hand back
     * the including schema instead of the file the include named.
     */
    @Test
    public void doesNotAnswerAnIncludeFromTheIncludingSchemasNamespace() throws Exception {
        bus.getExtension(OASISCatalogManager.class)
            .loadCatalogs(Thread.currentThread().getContextClassLoader(), "catalog/test-catalog.xml");

        // systemId names a file the catalog does not know; namespaceURI is mapped, as xs:include reports it.
        final LSInput input = new XmlCatalogResolver(bus)
            .resolveResource(null, "http://cxf.apache.org/test/ns/log", null, "no/such/include.xsd", null);

        assertNull("a schemaLocation that cannot be resolved must not fall back to the namespace", input);
    }

    @Test
    public void deniesImportByNamespaceWithoutCatalogEntry() throws Exception {
        assertNull("a namespace the catalog does not map has no location to fall back on",
                   new XmlCatalogResolver(bus)
                       .resolveResource(null, "http://cxf.apache.org/test/ns/unmapped", null, null, null));
    }

    @Test
    public void deniesResourceWithNeitherSystemIdNorNamespace() throws Exception {
        assertNull(new XmlCatalogResolver(bus).resolveResource(null, null, null, null, null));
    }

    /**
     * A catalog entry may name the identifier as the document writes it rather than absolutized, so the
     * lookup must try it that way too. CXF's own catalogs carry such entries.
     */
    @Test
    public void resolvesCatalogEntryWrittenAgainstTheRawSystemId() throws Exception {
        bus.getExtension(OASISCatalogManager.class)
            .loadCatalogs(Thread.currentThread().getContextClassLoader(), "catalog/test-catalog.xml");

        // a base URI is supplied, so the identifier would otherwise only be looked up absolutized
        final InputSource source = new XmlCatalogResolver(bus)
            .resolveEntity(null, "cxftest/echoSchema.xsd");

        assertNotNull("a rewriteSystem whose start string is not absolute must still match", source);
        assertTrue(read(source).contains("schema"));
    }

    /**
     * Every deployment that runs from jars rather than exploded classes resolves relative references against
     * an archive base. {@link java.net.URI#resolve} cannot do it, because a {@code jar:} URI is opaque and it
     * answers an opaque base with the reference unchanged; {@link org.apache.cxf.resource.URIResolver} can,
     * and this pins that the resolver leaves that to it.
     */
    @Test
    public void resolvesRelativeReferenceAgainstAJarBaseUri() throws Exception {
        final URL jarred = getClass().getResource("/org/apache/cxf/resource/resources/helloworld.bpr");
        final String base = "jar:" + jarred + "!/wsdl/hello_world.wsdl";

        final InputSource source = new XmlCatalogResolver(bus)
            .resolveEntity(null, "hello_world_schema.xsd");

        // the sibling does not exist in that archive, so what matters is that the base was not discarded:
        // an unresolvable sibling is denied, never silently answered from somewhere else
        assertNull(source);

        final LSInput sibling = new XmlCatalogResolver(bus)
            .resolveResource(null, null, null, "hello_world.wsdl", base);
        assertNotNull("a sibling inside the same archive must resolve", sibling);
        assertNotNull(sibling.getByteStream());
        sibling.getByteStream().close();
    }

    /**
     * Several jars may carry a catalog of the same name, so all of them must be read. Taking whichever the
     * class loader returns first would make resolution depend on classpath order.
     */
    @Test
    public void loadCatalogsReadsEveryCatalogWithThatName() throws Exception {
        final XmlCatalogResolver resolver = new XmlCatalogResolver(bus);
        // written as a classpath: URI, as SchemaHandler's default location is
        resolver.loadCatalogs(getClass().getClassLoader(), "classpath:/catalog/test-catalog.xml");

        assertNotNull("an entry from the catalog just loaded",
                      resolver.resolveEntity(null, CATALOG_MAPPED));
    }

    @Test
    public void deniesUnknownReference() throws Exception {
        assertNull(new XmlCatalogResolver(bus).resolveEntity(null, "no/such/resource.xsd"));
    }

    @Test
    public void deniesNullSystemId() throws Exception {
        assertNull(new XmlCatalogResolver(bus).resolveEntity("-//CXF//TEST//EN", null));
    }

    // --- each channel adapts the same decision ----------------------------------------------------------

    @Test
    public void entityResolverAdaptsToInputSource() throws Exception {
        final EntityResolver resolver = new XmlCatalogResolver(bus);

        final InputSource source = resolver.resolveEntity(null, LOCAL_SCHEMA);
        assertNotNull(source);
        assertNotNull("the system id is reported so nested references resolve against it", source.getSystemId());
    }

    @Test
    public void lsResourceResolverAdaptsToLSInput() throws Exception {
        final LSResourceResolver resolver = new XmlCatalogResolver(bus);

        final LSInput input = resolver.resolveResource(null, null, null, LOCAL_SCHEMA, null);
        assertNotNull(input);
        assertNotNull(input.getByteStream());
        assertNull(resolver.resolveResource(null, null, null, server.url(), null));
    }

    @Test
    public void uriResolverAdaptsToSource() throws Exception {
        final URIResolver resolver = new XmlCatalogResolver(bus);

        final Source source = resolver.resolve(LOCAL_SCHEMA, null);
        assertNotNull(source);
        assertTrue(source instanceof StreamSource);
        assertNull("an unmapped stylesheet module stays denied", resolver.resolve(server.url(), null));
    }

    @Test
    public void xmlResolverAdaptsToStream() throws Exception {
        final XMLResolver resolver = new XmlCatalogResolver(bus);

        final Object resolved = resolver.resolveEntity(null, LOCAL_SCHEMA, null, null);
        assertTrue("StAX requires an InputStream, an XMLStreamReader or an XMLEventReader",
                   resolved instanceof InputStream);
        assertNull(resolver.resolveEntity(null, server.url(), null, null));
    }

    private static String read(InputSource source) throws IOException {
        return read(source.getByteStream());
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return IOUtils.toString(in, StandardCharsets.UTF_8.name()).trim();
        }
    }

    /**
     * A minimal HTTP server that answers every request with {@link #REMOTE_BODY}, so a denied fetch is
     * distinguishable from one that merely failed to connect.
     */
    private static final class StubServer implements Closeable {
        private final ServerSocket socket;
        private final Thread thread;
        private final AtomicBoolean contacted = new AtomicBoolean();

        StubServer() throws IOException {
            socket = new ServerSocket(0);
            thread = new Thread(this::serve, "XmlCatalogResolverTest-stub-server");
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://localhost:" + socket.getLocalPort() + "/remote.xsd";
        }

        /** Whether anything has connected, so a denial is distinguishable from a fetch that merely failed. */
        boolean wasContacted() {
            return contacted.get();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept();
                     OutputStream out = accepted.getOutputStream()) {
                    contacted.set(true);
                    out.write(("HTTP/1.1 200 OK\r\n"
                               + "Content-Type: text/xml\r\n"
                               + "Content-Length: " + REMOTE_BODY.length() + "\r\n"
                               + "Connection: close\r\n\r\n"
                               + REMOTE_BODY).getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException e) {
                    return; // the socket was closed, or the client went away
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
