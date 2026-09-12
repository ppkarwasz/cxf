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

package org.apache.cxf.jaxrs.utils.schemas;

import java.util.Collections;
import java.util.List;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.extension.ExtensionManagerBus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class SchemaHandlerTest {

    /** Imports urn:cxf:test:catalogued by a remote location that only a catalog can answer. */
    private static final List<String> MAIN = Collections.singletonList("catalog-test/main.xsd");

    private static final String CATALOG = "classpath:/catalog-test/jax-rs-catalog.xml";

    private Bus bus;

    @Before
    public void setUp() {
        bus = new ExtensionManagerBus();
    }

    @After
    public void tearDown() {
        bus.shutdown(true);
    }


    @Test
    public void resolvesAnImportThroughTheConfiguredCatalog() {
        assertNotNull("the catalog names the imported schema, so the schema must compile",
                      SchemaHandler.createSchema(MAIN, CATALOG, bus));
    }

    @Test
    public void deniesTheSameImportWithoutACatalog() {
        try {
            SchemaHandler.createSchema(MAIN, null, bus);
            fail("without a catalog the import names a remote location, which must not be fetched");
        } catch (IllegalArgumentException expected) {
            // the securing floor answered the denied import with empty content
        }
    }
}
