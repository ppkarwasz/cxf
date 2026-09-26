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

package org.apache.cxf.tools.common.dom;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;




import org.w3c.dom.Document;

import org.xml.sax.SAXException;

import org.apache.commons.xml.secure.SecureSchemaFactory;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.staxutils.StaxUtils;

/**
 * (not thread safe)
 *
 */
public class ExtendedDocumentBuilder {

    private static final Logger LOG = LogUtils.getL7dLogger(ExtendedDocumentBuilder.class);

    private Schema schema;

    public ExtendedDocumentBuilder() {
    }

    private InputStream getSchemaLocation() {
        String toolspec = "/org/apache/cxf/tools/common/toolspec/tool-specification.xsd";
        return getClass().getResourceAsStream(toolspec);
    }

    public void setValidating(boolean validate) {
        if (validate) {
            try {
                SchemaFactory schemaFactory = SecureSchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                this.schema = schemaFactory.newSchema(new StreamSource(getSchemaLocation()));
            } catch (SAXException e) {
                LOG.log(Level.SEVERE, "SCHEMA_FACTORY_EXCEPTION_MSG");
            }
        } else {
            this.schema = null;
        }
    }

    public Document parse(InputStream in) throws SAXException, IOException, XMLStreamException {
        if (in == null && LOG.isLoggable(Level.FINE)) {
            LOG.fine("ExtendedDocumentBuilder trying to parse a null inputstream");
        }
        if (this.schema != null) {
            //validating, so need a builder that carries the schema
            try {
                return DOMUtils.createNSDocumentBuilder(this.schema).parse(in);
            } catch (UnsupportedOperationException e) {
                LOG.log(Level.WARNING, "DOC_PARSER_NOT_SUPPORTED", e);
            }
        }
        return StaxUtils.read(in);
    }

}
