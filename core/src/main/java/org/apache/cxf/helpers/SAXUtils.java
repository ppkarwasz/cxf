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

package org.apache.cxf.helpers;

import java.util.logging.Level;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

import org.apache.commons.xml.secure.SecureSAXParserFactory;
import org.apache.cxf.common.logging.LogUtils;

/**
 * Creates the SAX parsers CXF reads XML with, the counterpart of {@link DOMUtils} for the SAX API.
 *
 * @since 4.3.0
 */
public final class SAXUtils {

    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    private SAXUtils() {
    }

    /**
     * Creates a namespace-aware {@link SAXParserFactory}.
     *
     * <p>A DOCTYPE declaration is refused by policy: an implementation that can refuse one fails the
     * parse outright, and on one that cannot, the declaration is read, but the external resources it
     * names resolve to nothing.</p>
     *
     * @return a new parser factory.
     */
    public static SAXParserFactory createNSSAXParserFactory() {
        SAXParserFactory f = SecureSAXParserFactory.newNSInstance();
        try {
            // Where the implementation can refuse a DOCTYPE, let it fail the parse.
            f.setFeature(DISALLOW_DOCTYPE_DECL, true);
        } catch (ParserConfigurationException | SAXNotRecognizedException | SAXNotSupportedException e) {
            // It cannot, so the DOCTYPE is read; what it points at is still ignored by Commons Secure XML.
            LogUtils.getL7dLogger(SAXUtils.class).log(Level.FINE,
                "This implementation does not support `" + DISALLOW_DOCTYPE_DECL + "`. "
                    + "DOCTYPE declarations will be ignored instead.", e);
        }
        return f;
    }

    /**
     * Creates a namespace-aware {@link SAXParser}.
     *
     * <p>A DOCTYPE declaration is refused by policy: an implementation that can refuse one fails the
     * parse outright, and on one that cannot, the declaration is read, but the external resources it
     * names resolve to nothing.</p>
     *
     * @return a new parser.
     */
    public static SAXParser createNSSAXParser() {
        try {
            return createNSSAXParserFactory().newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            // Unreachable in practice: JAXP implementations fail eagerly, as the factory is configured.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates a namespace-aware {@link XMLReader}.
     *
     * <p>A DOCTYPE declaration is refused by policy: an implementation that can refuse one fails the
     * parse outright, and on one that cannot, the declaration is read, but the external resources it
     * names resolve to nothing.</p>
     *
     * @return a new reader.
     */
    public static XMLReader createNSXMLReader() {
        try {
            return createNSSAXParser().getXMLReader();
        } catch (SAXException e) {
            // Unreachable in practice: JAXP implementations fail eagerly, as the factory is configured.
            throw new IllegalStateException(e);
        }
    }
}
