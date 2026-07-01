package com.agfa.orbis.common.mockengine;

import com.agfa.orbis.common.mockengine.impl.OpenApiStubGenerator;


public class StubGeneratorCli {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: StubGeneratorCli <openapi-file> <output-dir>");
            System.exit(1);
        }
        int count = new OpenApiStubGenerator().generate(args[0], args[1]);
        System.out.println("Generated " + count + " stub(s) → " + args[1]);
    }
}
