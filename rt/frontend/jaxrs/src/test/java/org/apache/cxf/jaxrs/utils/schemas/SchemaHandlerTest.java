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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.extension.ExtensionManagerBus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
            // the import went unanswered, so the schema could not be built
        }
    }

    /**
     * The import the schema names is reachable, so a denial has to be told apart from a fetch that merely
     * failed: the host must never be contacted.
     */
    @Test
    public void doesNotFetchAnImportItWasNotGivenACatalogFor() throws Exception {
        try (StubServer server = new StubServer()) {
            final List<String> importing =
                Collections.singletonList(writeSchemaImporting(server.url()));
            try {
                SchemaHandler.createSchema(importing, null, bus);
            } catch (IllegalArgumentException expected) {
                // the import went unanswered
            }
            assertFalse("the schema named a remote import, which must not be fetched",
                        server.wasContacted());
        }
    }

    /** Writes a schema importing the given location, and returns the path it wrote it to. */
    private String writeSchemaImporting(String location) throws Exception {
        final File f = File.createTempFile("importing", ".xsd");
        f.deleteOnExit();
        Files.writeString(f.toPath(),
            "<?xml version='1.0' encoding='UTF-8'?>"
            + "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'"
            + " targetNamespace='urn:cxf:test:importing' elementFormDefault='qualified'>"
            + "<xs:import namespace='urn:cxf:test:remote' schemaLocation='" + location + "'/>"
            + "<xs:element name='importingElement' type='xs:string'/>"
            + "</xs:schema>");
        return f.getAbsolutePath();
    }

    /** Answers every request, so being contacted is observable. */
    private static final class StubServer implements Closeable {
        private final ServerSocket socket;
        private final AtomicBoolean contacted = new AtomicBoolean();

        StubServer() throws IOException {
            socket = new ServerSocket(0);
            final Thread t = new Thread(this::serve, "SchemaHandlerTest-stub-server");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "http://localhost:" + socket.getLocalPort() + "/remote.xsd";
        }

        boolean wasContacted() {
            return contacted.get();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept();
                     OutputStream out = accepted.getOutputStream()) {
                    contacted.set(true);
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                              .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
