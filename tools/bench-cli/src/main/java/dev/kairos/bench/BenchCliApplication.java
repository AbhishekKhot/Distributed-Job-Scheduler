package dev.kairos.bench;

import picocli.CommandLine;

/**
 * Entry point of the benchmark CLI.
 *
 * <p>Creates jobs at a target rate, drives the load scenarios and verifies afterwards that no
 * run was lost and none was executed twice.
 */
public class BenchCliApplication {

    /**
     * Starts the application.
     *
     * @param args benchmark command-line arguments
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new BenchCommand()).execute(args));
    }
}
