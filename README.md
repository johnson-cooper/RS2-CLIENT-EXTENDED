# RS2 Progression Client Agent

A Java Agent project for experimenting with runtime instrumentation on a closed-source RS2-style client.  
This project is built in **Eclipse** and exported as a JAR for use with `-javaagent`.

## What this project does

- Loads as a Java Agent at JVM startup
- Logs class loading for discovery
- Can inspect and transform bytecode at runtime
- Supports safe experimentation with obfuscated RS2 client internals
- Uses ASM for bytecode manipulation

## Requirements

- Java 8 recommended
- Eclipse IDE
- ASM libraries:
  - `asm`
  - `asm-commons`
  - `asm-tree` if needed
- The RS2 client JAR you want to run the agent with

## Project layout

```text
rs2-agent/
├── src/
│   └── agent/
│       ├── AgentMain.java
│       ├── LoggingTransformer.java
│       ├── SizeTransformer.java
│       ├── WindowStretch.java
│       └── other helper classes...
├── lib/
│   ├── asm-9.x.jar
│   ├── asm-commons-9.x.jar
│   └── asm-tree-9.x.jar
├── MANIFEST.MF
└── README.md
```

## Adding ASM libraries in Eclipse

1. Create a `lib` folder inside the project.
2. Put the ASM JARs inside `lib`.
3. In Eclipse:
   - Right-click the project
   - Choose **Build Path**
   - Choose **Configure Build Path**
   - Open the **Libraries** tab
   - Click **Add JARs** or **Add External JARs**
   - Select the ASM JAR files
   - Apply and close

## Manifest file

Create a file called `MANIFEST.MF` in the project root.

```
Manifest-Version: 1.0
Premain-Class: agent.AgentMain
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

### Important

- Keep a blank line at the end of the file.
- Make sure `Premain-Class` matches your package and class name exactly.

## Main agent entry point

The agent starts from `agent.AgentMain`.

### Example

```java
package agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] loaded");

        // Register transformers here
        inst.addTransformer(new LoggingTransformer(), false);
        inst.addTransformer(new SizeTransformer(), true);

        // Optional runtime helper
        WindowStretch.start();

        System.out.println("[Agent] transformers installed");
    }
}
```

## Eclipse compile / export workflow

1. Build the project in Eclipse
2. Right-click the project
3. Choose **Export**
4. Select **Java > JAR file**
5. Choose the source folders and output location
6. On the manifest screen:
   - Select **Use existing manifest**
   - Point it to your `MANIFEST.MF`
7. Finish the export

## Important export settings

When exporting the JAR, make sure you handle dependencies correctly.

### Option 1: Self-contained JAR (recommended)

- Package required libraries into the generated JAR.

### Option 2: External dependencies

- ASM JARs must be available at runtime or the agent will fail.

## Running the agent

```bash
java -javaagent:agent.jar -jar Client.jar
```

## What to expect on startup

```
[Agent] loaded
[Agent] transformers installed
[Load] ...
[Candidate] ...
[Hook] ...
```

## Troubleshooting

### ClassNotFoundException (ASM)

ASM libraries are missing from the runtime or JAR.

### Premain-Class not found

Manifest is incorrect or missing.

### VerifyError

Transformer produced invalid bytecode:
- Frames not computed correctly
- Stack state broken
- Transformation too aggressive

### NoClassDefFoundError

Helper class not bundled into the agent JAR.

### Client starts but nothing happens

Transformer is not matching the target class/method.

## Development notes

- Start with logging only
- Confirm class loading
- Add transformations gradually
- Avoid modifying JDK classes
- Keep changes minimal

## Recommended workflow

1. Add logging transformer
2. Confirm target class discovery
3. Add a small hook
4. Test after each change
5. Export fresh JAR
6. Run with `-javaagent`

## Notes on RS2-style clients

These clients are often:

- Obfuscated
- Old bytecode style
- AWT-based rendering
- Sensitive to stack/frame issues

### Best practices

- Make small changes
- Avoid broad instrumentation
- Target one class at a time

## License

This project is for personal experimentation and learning.

## Build checklist

- Project compiles cleanly
- ASM JARs added
- MANIFEST.MF exists
- Premain-Class correct
- Helper classes included
- Agent runs with `-javaagent`
- No VerifyError

## Example command

```bash
java -javaagent:agent.jar -jar Client.jar
```

## Final note

If you change transformer logic, rebuild and re-export before testing again.
