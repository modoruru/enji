package su.enji;

import org.json.JSONObject;
import su.enji.model.Origin;
import su.enji.model.Project;
import su.enji.model.Token;
import su.enji.model.Variable;
import su.enji.model.core.CoreBrand;
import su.enji.util.JSONUtil;
import su.enji.util.JavaUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;

import static su.enji.util.PrintUtil.printFatal;
import static su.enji.util.PrintUtil.printInfo;

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
        }
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
            Enji enji = new Enji(workingDirectory);
            Optional<String> updateError = enji.update(null, null);
            if(updateError.isPresent()) {
                printFatal("problem auto-updating the project");
                printFatal(updateError.get());
                printFatal("execution aborted.");
                return;
            }
        }

        JSONObject runCommandBody = body.optJSONObject("run_command", null);
        String command;
        if(runCommandBody == null || (command = runCommandBody.optString("command", null)) == null) {
            printFatal("run command is not set");
            System.exit(0);
            return;
        }

        JavaUtil.start(
                command,
                workingDirectory
        );
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
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

        try {
            Files.setAttribute(enjiDirectory.toPath(), "dos:hidden", Boolean.TRUE);
        }
        catch (Exception _) {
        }

        printInfo("welcome to enji!");
        printInfo("please provide a url to YAML configuration which would be installed.");
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
        Optional<String> downloadProjectFileError = Enji.downloadProjectFile(projectFile, uri);
        if(downloadProjectFileError.isPresent()) {
            printFatal("problem downloading project file");
            printFatal(downloadProjectFileError.get());
            printFatal("execution aborted.");
            return;
        }

        printInfo("resolving...");

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
        printInfo("Project \"" + project.name() + "\"");
        printInfo("\"" + project.description() + "\"");

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
                System.out.println();
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

            for (Variable requiredVariable : project.variables()) {
                System.out.println();

                String name = requiredVariable.name();
                String defaultValue = requiredVariable.defaultValue();

                StringBuilder variableInfo = new StringBuilder("Variable \"");
                variableInfo.append(name).append("\". \n");
                variableInfo.append("\"").append(requiredVariable.description()).append("\"\n");

                if (defaultValue != null) {
                    variableInfo.append("default value: \"").append(defaultValue).append("\", leave string empty to use default value\n");
                }

                System.out.print(variableInfo);

                String value;
                while (true) {
                    System.out.print("value > ");

                    value = scanner.nextLine();
                    if (value.isEmpty() && defaultValue != null) {
                        value = defaultValue;
                        break;
                    }

                    if (requiredVariable.matches(value) && !value.isEmpty()) break;
                    else printFatal("doesn't matches variable pattern");
                }

                variables.put(name, value);
                printInfo("set \"" + name + "\" variable to \"" + value + "\"");
            }
        }

        if(!variables.isEmpty() || !tokens.isEmpty()) System.out.println();
        printInfo("choose java binary, leave empty to use \"java\"");
        String javaPath;
        while (true) {
            System.out.print("java binary path > ");

            javaPath = scanner.nextLine();
            if(javaPath.isEmpty()) javaPath = "java";
            if(JavaUtil.checkJavaInstallation(javaPath)) break;

            printFatal("\"" + javaPath + "\" installation is not valid");
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

        System.out.println();
        if(project.core().brand() != CoreBrand.VELOCITY) {
            printInfo("do you agree with Minecraft EULA (https://aka.ms/MinecraftEULA)?");
            printInfo("type \"yes\" to indicate your agreement, or anything else to deny it.");
            System.out.print("> ");

            String answer = scanner.nextLine();
            if(!answer.equalsIgnoreCase("yes") && !answer.equalsIgnoreCase("y"))
                printInfo("you've made your choice!");
            else {
                printInfo("saving your EULA agreement...");
                try (FileWriter writer = new FileWriter(new File(workingDirectory, "eula.txt"))) {
                    writer.write("eula=true");
                    writer.flush();
                }
                catch (IOException e) {
                    printFatal("unable to save EULA agreement");
                }
            }
            System.out.println();
        }

        printInfo("installation process is done, here's quick summary");
        Origin origin = project.origin();
        System.out.printf("""
                "%s"
                description: "%s"
                
                origin:
                  repository: %s
                  branch: %s
                  path: %s
                
                run command: "enji run"
                """,
                project.name(),
                project.description(),
                origin.repo(),
                origin.branch(),
                origin.path()
        );

        System.exit(0);
    }

    private static void modify() {
        printFatal("modify command is WIP");
        if(true) return;

        File workingDirectory = resolveWorkingDirectory();
        if(workingDirectory == null) return;

        File installationFile = new File(workingDirectory, ".enji/installation.json");
        if(!installationFile.exists()) {
            printFatal("there's no active installation. run enji install first.");
            return;
        }

        /*
        what user should be able to modify
        - variables
        - java path
        - tokens
        */

        printInfo("what would you like to modify?");
        printInfo("variables, javapath or tokens");
        printInfo("type \"exit\" to close enji modify");
        System.out.print("option > ");

        Scanner scanner = new Scanner(System.in);
        String option = scanner.nextLine();
        switch (option) {
            case "variables" -> {

            }
            case "javapath" -> {

            }
            case "tokens" -> {

            }
            default -> {
                printFatal("no such option.");
                return;
            }
        }
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