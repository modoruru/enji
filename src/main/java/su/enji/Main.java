package su.enji;

import org.json.JSONObject;
import su.enji.model.Project;
import su.enji.model.Token;
import su.enji.model.Variable;
import su.enji.util.IOUtil;
import su.enji.util.JSONUtil;
import su.enji.util.JavaUtil;
import su.enji.util.Pair;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

import static su.enji.util.PrintUtil.*;

public class Main {

    public static void main(String[] args) {
        if(args.length == 0) {
            printHelp();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "run" -> run();
            case "install" -> install();
            case "modify" -> modify();
            default -> printHelp();
        };
    }

    private static File resolveWorkingDirectory() {
        File file = new File(System.getProperty("user.dir"));

        if(!file.exists()) {
            printFatal("couldn't find working directory.");
            return null;
        }

        return file;
    }

    private static void run() {
        File workingDirectory = resolveWorkingDirectory();
        if(workingDirectory == null) return;

        File installationFile = new File(workingDirectory, ".enji/installation.json");
        if(!installationFile.exists()) {
            printFatal("there's no active installation. run enji install first.");
            return;
        }

        JSONObject body = JSONUtil.readFile(installationFile);
        if(body.optBoolean("auto_update", false)) {
            // todo: update
        }

        String runCommand = body.optString("run_command", null);
        if(runCommand == null) {
            printFatal("run command is not set");
            System.exit(0);
            return;
        }

        JavaUtil.start(
                runCommand,
                workingDirectory
        );
    }

    private static void install() {
        File workingDirectory = resolveWorkingDirectory();
        if(workingDirectory == null) return;

        File installationFile = new File(workingDirectory, ".enji/installation.json");
        if(installationFile.exists()) {
            printFatal("there's already existing enji installation. please delete .enji folder or change the directory.");
            return;
        }

        File[] files = workingDirectory.listFiles();
        assert files != null;

        if(files.length != 0 && (files.length == 1 && !files[0].getName().toLowerCase().contains("enji"))) {
            printFatal("installation directory should be empty");
            return;
        }

        File enjiDirectory = installationFile.getParentFile();
        enjiDirectory.mkdirs();

        printInfo("welcome to enji! please provide a url to YAML configuration which would be installed.");
        System.out.print("project uri > ");

        Scanner scanner = new Scanner(System.in);
        String rawUrl = scanner.nextLine();

        URI uri;
        try {
            uri = URI.create(rawUrl);
        }
        catch (IllegalArgumentException _) {
            printFatal("this is not a uri.");
            return;
        }

        File projectFile = new File(enjiDirectory, "project.yml");
        try {
            Pair<InputStream, Long> inputStreamAndSize = IOUtil.resolveToInputStream(uri);
            if(inputStreamAndSize == null) {
                printFatal("unable to resolve project configuration");
                return;
            }

            try (InputStream inputStream = inputStreamAndSize.first(); FileOutputStream fos = new FileOutputStream(projectFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                fos.flush();
            }
        }
        catch (Exception e) {
            printFatal("unable to resolve project configuration");
            throw new RuntimeException(e);
        }

        printInfo("parsing...");

        Enji enji = new Enji(workingDirectory);
        Optional<String> parseError = enji.readProject(projectFile);
        if(parseError.isPresent()) {
            printFatal("problem reading project");
            printFatal(parseError.get());
            printFatal("execution aborted.");
            return;
        }

        Project project = enji.project();

        System.out.println();
        printInfo("\"" + project.name() + "\" project.");
        printInfo("description: \"" + project.description() + "\"");
        System.out.println();

        Map<Token, String> tokens = new HashMap<>();
        if(!project.tokens().isEmpty()) {
            Set<Token> requiredTokens = project.tokens();
            printInfo("this project requires several tokens: " + String.join(
                    ", ",
                    requiredTokens.stream()
                            .map(Token::name)
                            .map(String::toLowerCase)
                            .toList()
            ));

            for (Token requiredTokenType : requiredTokens) {
                String token;
                while (true) {
                    System.out.print(requiredTokenType.name().toLowerCase() + " token > ");

                    token = scanner.nextLine();
                    if(token.isEmpty()) printFatal("provide non-empty token.");
                    else break;
                }

                tokens.put(requiredTokenType, token);
            }
        }

        Map<String, String> variables = new HashMap<>();
        if(!project.variables().isEmpty()) {
            if(!tokens.isEmpty()) System.out.println();

            List<Variable> requiredVariables = project.variables();

            printInfo("this project requires several variables to be set: " + String.join(
                    ", ",
                    requiredVariables.stream()
                            .map(Variable::name)
                            .map(String::toLowerCase)
                            .toList()
            ));

            var iterator = requiredVariables.iterator();
            while (iterator.hasNext()) {
                System.out.println();

                Variable requiredVariable = iterator.next();
                String name = requiredVariable.name();
                String defaultValue = requiredVariable.defaultValue();

                StringBuilder variableInfo = new StringBuilder("set variable \"");
                variableInfo.append(name).append("\". \n");
                variableInfo.append("description: \"").append(requiredVariable.description()).append("\"\n");

                if(defaultValue != null) {
                    variableInfo.append("default value: ").append(defaultValue).append("\n");
                    variableInfo.append("leave string empty to use default value\n");
                }

                System.out.print(variableInfo);

                String value;
                while (true) {
                    System.out.print("value > ");

                    value = scanner.nextLine();
                    if(value.isEmpty() && defaultValue != null) {
                        value = defaultValue;
                        break;
                    }

                    if(requiredVariable.matches(value) && !value.isEmpty()) break;
                    else printFatal("doesn't matches variable pattern");
                }

                variables.put(name, value);
                printInfo("set \"" + name + "\" variable to \"" + value + "\"");

                if(iterator.hasNext()) System.out.println();
            }
        }

        printInfo("choose java binary, leave empty to use \"java\"");
        String javaPath;
        while (true) {
            System.out.print("java binary path > ");

            javaPath = scanner.nextLine();
            if(JavaUtil.checkJavaInstallation(javaPath)) break;

            printFatal("java installation is not valid");
        }

        printInfo("all set! installing project...");

        Optional<String> installError = enji.install(
                tokens,
                variables,
                javaPath
        );
        if(installError.isPresent()) {
            printFatal("problem installing project");
            printFatal(installError.get());
            printFatal("execution aborted.");
            System.exit(0);
            return;
        }

        System.exit(0);
    }

    private static void modify() {

    }

    private static void printHelp() {
        printInfo(
                """
                enji help:
                    help - shows this message
                    run - runs already installed in this environment project
                    install - creates new project installation
                    modify - modifies already installed project
                """
        );
    }

}