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
 * Copyright (c) 2019 Matthijs van den Bos. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.golang.GoModJsonParser;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.owasp.dependencycheck.data.nvd.ecosystem.Ecosystem;

/**
 * Go mod dependency analyzer.
 *
 * @author Matthijs van den Bos
 */
@Experimental
public class GolangModAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(GolangModAnalyzer.class);

    /**
     * A descriptor for the type of dependencies processed or added by this
     * analyzer.
     */
    public static final String DEPENDENCY_ECOSYSTEM = Ecosystem.GOLANG;

    /**
     * The name of the analyzer.
     */
    private static final String ANALYZER_NAME = "Golang Mod Analyzer";

    /**
     * Lock file name. Please note that go.sum is NOT considered a lock file and
     * may contain dependencies that are no longer used and dependencies of
     * dependencies. According to here, go.mod should be used for reproducible
     * builds:
     * https://github.com/golang/go/wiki/Modules#is-gosum-a-lock-file-why-does-gosum-include-information-for-module-versions-i-am-no-longer-using
     */
    public static final String GO_MOD = "go.mod";

    private static String goPath = "go";
    /**
     * The file filter for Gopkg.lock
     */
    private static final FileFilter GO_MOD_FILTER = FileFilterBuilder.newInstance()
            .addFilenames(GO_MOD)
            .build();

    /**
     * Returns the name of the Golang Mode Analyzer.
     *
     * @return the name of the analyzer
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Tell that we are used for information collection.
     *
     * @return INFORMATION_COLLECTION
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return AnalysisPhase.INFORMATION_COLLECTION;
    }

    /**
     * Returns the key name for the analyzers enabled setting.
     *
     * @return the key name for the analyzers enabled setting
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_GOLANG_MOD_ENABLED;
    }

    /**
     * Returns the FileFilter
     *
     * @return the FileFilter
     */
    @Override
    protected FileFilter getFileFilter() {
        return GO_MOD_FILTER;
    }

    /**
     * Attempts to determine the path to `go`.
     *
     * @return the path to `go`
     */
    private String getGo() {
        synchronized (this) {
            if (goPath == null) {
                final String path = getSettings().getString(Settings.KEYS.ANALYZER_GOLANG_PATH);
                if (path == null) {
                    goPath = "go";
                } else {
                    final File goFile = new File(path);
                    if (goFile.isFile()) {
                        goPath = goFile.getAbsolutePath();
                    } else {
                        LOGGER.warn("Provided path to `go` executable is invalid. Trying default location. If you do want to set it, please set the `{}` property",
                                Settings.KEYS.ANALYZER_GOLANG_PATH
                        );
                        goPath = "go";
                    }
                }
            }
        }
        return goPath;
    }

    /**
     * Launches `go mod help` to test if go is installed.
     *
     * @return a reference to the launched process
     * @throws AnalysisException thrown if there is an issue launching `go mod`
     */
    private Process testGoMod(File folder) throws AnalysisException {
        if (!folder.isDirectory()) {
            throw new AnalysisException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }

        final List<String> args = new ArrayList<>();
        args.add(getGo());
        args.add("mod");
        args.add("edit");
        args.add("-json");

        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(folder);
        try {
            LOGGER.debug("Launching: {} from {}", args, folder);
            return builder.start();
        } catch (IOException ioe) {
            throw new AnalysisException("go initialization failure; this error can be ignored if you are not analyzing Go. "
                    + "Otherwise ensure that go is installed and the path to go is correctly specified", ioe);
        }
    }

    /**
     * Launches `go list -json -m all` in the given folder.
     *
     * @param folder the working folder
     * @return a reference to the launched process
     * @throws AnalysisException thrown if there is an issue launching `go mod`
     */
    private Process launchGoListAll(File folder) throws AnalysisException {
        if (!folder.isDirectory()) {
            throw new AnalysisException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }

        final List<String> args = new ArrayList<>();
        args.add(getGo());
        args.add("list");
        args.add("-json");
        args.add("-m");
        args.add("all");

        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(folder);
        try {
            LOGGER.debug("Launching: {} from {}", args, folder);
            return builder.start();
        } catch (IOException ioe) {
            throw new AnalysisException("go initialization failure; this error can be ignored if you are not analyzing Go. "
                    + "Otherwise ensure that go is installed and the path to go is correctly specified", ioe);
        }
    }

    /**
     * Launches `go list -json -m readonly` in the given folder.
     *
     * @param folder the working folder
     * @return a reference to the launched process
     * @throws AnalysisException thrown if there is an issue launching `go mod`
     */
    private Process launchGoListReadonly(File folder) throws AnalysisException {
        if (!folder.isDirectory()) {
            throw new AnalysisException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }

        final List<String> args = new ArrayList<>();
        args.add(getGo());
        args.add("list");
        args.add("-json");
        args.add("-m");
        args.add("readonly");

        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(folder);
        try {
            LOGGER.debug("Launching: {} from {}", args, folder);
            return builder.start();
        } catch (IOException ioe) {
            throw new AnalysisException("go initialization failure; this error can be ignored if you are not analyzing Go. "
                    + "Otherwise ensure that go is installed and the path to go is correctly specified", ioe);
        }
    }

    /**
     * Initialize the go mod analyzer; ensures that go is installed and can be
     * called.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException never thrown
     */
    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings(justification = "The fallthrough is intentional to avoid code duplication", value = {"SF_SWITCH_NO_DEFAULT"})
    @Override
    protected void prepareFileTypeAnalyzer(Engine engine) throws InitializationException {
        setEnabled(false);
        final Process process;
        try {
            process = testGoMod(getSettings().getTempDirectory());
        } catch (AnalysisException ae) {
            final String msg = String.format("Exception from go process: %s. Disabling %s", ae.getCause(), ANALYZER_NAME);
            throw new InitializationException(msg, ae);
        } catch (IOException ex) {
            throw new InitializationException("Unable to create temporary file, the Go Mod Analyzer will be disabled", ex);
        }

        final int exitValue;
        try {
            exitValue = process.waitFor();
        } catch (InterruptedException ex) {
            final String msg = String.format("Go mod process was interrupted. Disabling %s", ANALYZER_NAME);
            Thread.currentThread().interrupt();
            throw new InitializationException(msg);
        }

        final int expectedNoModuleFoundExitValue = 1;
        final int possiblyGoTooOldExitValue = 2;
        final int goExecutableNotFoundExitValue = 127;

        switch (exitValue) {
            case expectedNoModuleFoundExitValue:
                setEnabled(true);
                LOGGER.debug("{} is enabled.", ANALYZER_NAME);
                return;
            case goExecutableNotFoundExitValue:
                throw new InitializationException(String.format("Go executable not found. Disabling %s: %s", ANALYZER_NAME, exitValue));
            case possiblyGoTooOldExitValue:
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    if (!reader.ready()) {
                        LOGGER.warn("Go mod error stream unexpectedly not ready. Disabling {}", ANALYZER_NAME);
                        throw new InitializationException("Go mod error stream unexpectedly not ready.");
                    } else {
                        final String line = reader.readLine();
                        if (line == null) {
                            LOGGER.warn("An error occurred calling `go` - no output could be read. Disabling {}.", ANALYZER_NAME);
                            throw new InitializationException("Error calling `go` - no output could be read.");
                        }
                        if (line.contains("unknown subcommand \"mod\"")) {
                            LOGGER.warn("Your version of `go` does not support modules. Disabling {}. Error: `{}`", ANALYZER_NAME, line);
                            throw new InitializationException("Go version does not support modules.");
                        }
                    }
                } catch (UnsupportedEncodingException ex) {
                    throw new InitializationException("Unexpected go encoding.", ex);
                } catch (IOException ex) {
                    throw new InitializationException("Unable to read go output.", ex);
                }
            // fall through
            default:
                final String msg = String.format("Unexpected exit code from go process. Disabling %s: %s", ANALYZER_NAME, exitValue);
                throw new InitializationException(msg);
        }
    }

    /**
     * Analyzes go packages and adds evidence to the dependency.
     *
     * @param dependency the dependency being analyzed
     * @param engine the engine being used to perform the scan
     * @throws AnalysisException thrown if there is an unrecoverable error
     * analyzing the dependency
     */
    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        //engine.removeDependency(dependency);

        final int exitValue;

        final File parentFile = dependency.getActualFile().getParentFile();

        Process process = launchGoListAll(parentFile);
        try {
            process = evaluateProcessErrorStream(process, parentFile);
            try (InputStream inputStream = process.getInputStream()) {
                GoModJsonParser.process(inputStream).forEach(goDep
                        -> engine.addDependency(goDep.toDependency(dependency))
                );
            }
            process.waitFor();
            process.getInputStream().close();
            process.getErrorStream().close();
            exitValue = process.exitValue();
            if (exitValue < 0 || exitValue > 1) {
                final String msg = String.format("Unexpected exit code from go process; exit code: %s", exitValue);
                throw new AnalysisException(msg);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("go process interrupted", ie);
        } catch (IOException ex) {
            throw new AnalysisException("Error closing the go process", ex);
        }

    }

    /**
     * Reads the error stream and restarts the process with a different command
     * under certain error conditions.
     *
     * @param process the go process
     * @param directory the directory where the process is running
     * @return a reference to the process to evaluate; which may not be the
     * process passed as an argument
     * @throws InterruptedException thrown if the process is interrupted
     */
    private Process evaluateProcessErrorStream(Process process, File directory) throws AnalysisException, InterruptedException {
        try {
            String error = null;
            final InputStream errorStream = process.getErrorStream();
            if (errorStream.available() > 0) {
                error = IOUtils.toString(errorStream, StandardCharsets.UTF_8.name());
            }
            if (error != null) {
                LOGGER.warn("Warnings from go {}", error);
                if (error.contains("can't compute 'all' using the vendor directory")) {
                    LOGGER.warn("Switching to `go list -json -m readonly`");
                    process.destroy();
                    return evaluateProcessErrorStream(launchGoListReadonly(directory), directory);
                }
            }
        } catch (IOException ioe) {
            LOGGER.warn("go mod failure", ioe);
            throw new AnalysisException("Error running go: " + ioe.getMessage(), ioe);
        }
        return process;
    }
}
