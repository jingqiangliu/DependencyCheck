/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2015 The OWASP Foundation. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.golang;

import java.io.IOException;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.json.JsonReaderFactory;
import javax.json.stream.JsonParserFactory;
import org.owasp.dependencycheck.utils.JsonArrayFixingInputStream;

/**
 * Parses json output from `go list -json -m all`.
 *
 * @author Matthijs van den Bos
 */
@ThreadSafe
public final class GoModJsonParser {

    /**
     * The LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(GoModJsonParser.class);

    private GoModJsonParser() {
    }

    /**
     * Process the input stream to create the list of dependencies.
     *
     * @param inputStream the InputStream to parse
     * @return the list of dependencies
     * @throws AnalysisException thrown when there is an error parsing the
     * results of `go mod`
     */
    public static List<GoModDependency> process(InputStream inputStream) throws AnalysisException {
        LOGGER.debug("Beginning go.mod processing");
        List<GoModDependency> goModDependencies = new ArrayList<>();
        try (JsonArrayFixingInputStream jsonStream = new JsonArrayFixingInputStream(inputStream)) {
//            String array = IOUtils.toString(inputStream, UTF_8);
//            array = array.trim().replace("}", "},");
//            array = "[" + array.substring(0, array.length() - 1) + "]";
//
//            JsonReader reader = Json.createReader(new StringReader(array));
            JsonReaderFactory factory = Json.createReaderFactory(null);
            try (JsonReader reader = factory.createReader(jsonStream, java.nio.charset.StandardCharsets.UTF_8)) {
                final JsonArray modules = reader.readArray();
                for (JsonObject module : modules.getValuesAs(JsonObject.class)) {
                    final String path = module.getString("Path");
                    String version = module.getString("Version", null);
                    if (version != null && version.startsWith("v")) {
                        version = version.substring(1);
                    }
                    goModDependencies.add(new GoModDependency(path, version));
                }
            }
        } catch (JsonParsingException jsonpe) {
            throw new AnalysisException("Error parsing stream", jsonpe);
        } catch (JsonException jsone) {
            throw new AnalysisException("Error reading stream", jsone);
        } catch (IllegalStateException ise) {
            throw new AnalysisException("Illegal state in go mod stream", ise);
        } catch (ClassCastException cce) {
            throw new AnalysisException("JSON not exactly matching output of `go list -json -m all`", cce);
        } catch (IOException ex) {
            throw new AnalysisException("Error reading output of `go list -json -m all`", ex);
        }
        return goModDependencies;
    }
}
