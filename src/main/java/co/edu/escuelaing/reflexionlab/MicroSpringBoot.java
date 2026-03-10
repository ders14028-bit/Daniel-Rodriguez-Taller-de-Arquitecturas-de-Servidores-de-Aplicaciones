package co.edu.escuelaing.reflexionlab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class MicroSpringBoot {

    public static void main(String[] args) throws IOException {
        Map<String, HandlerMethod> routes;
        try {
            if (args.length >= 1) {
                routes = ControllerLoader.load(args[0]);
            } else {
                routes = ControllerLoader.loadAll("co.edu.escuelaing.reflexionlab");
                if (routes.isEmpty()) {
                    System.out.println("No @RestController classes found in package: co.edu.escuelaing.reflexionlab");
                    return;
                }
            }
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            System.out.println("Error loading controller: " + e.getMessage());
            return;
        }

        int port = 8080;
        MicroHttpServer server = new MicroHttpServer(port, routes);
        System.out.println("Micro server running on http://localhost:" + port);
        server.start();
    }

    static class ControllerLoader {

        private ControllerLoader() {
        }

        static Map<String, HandlerMethod> load(String className) throws ReflectiveOperationException {
            Class<?> controllerClass = Class.forName(className);
            return loadControllerClass(controllerClass);
        }

        static Map<String, HandlerMethod> loadAll(String basePackage) throws ReflectiveOperationException {
            Map<String, HandlerMethod> routes = new HashMap<>();
            String packagePath = basePackage.replace('.', '/');

            try {
                Path codeLocation = Paths.get(MicroSpringBoot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isDirectory(codeLocation)) {
                    scanFromDirectory(routes, codeLocation.resolve(packagePath), basePackage);
                } else {
                    scanFromJar(routes, codeLocation, packagePath);
                }
            } catch (Exception e) {
                throw new ReflectiveOperationException("Error resolving package path: " + basePackage, e);
            }

            return routes;
        }

        private static void scanFromDirectory(Map<String, HandlerMethod> routes, Path rootPath, String basePackage)
                throws ReflectiveOperationException {
            if (!Files.exists(rootPath)) {
                return;
            }

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> {
                            String className = toClassName(rootPath, basePackage, path);
                            tryLoadController(routes, className);
                        });
            } catch (Exception e) {
                throw new ReflectiveOperationException("Error scanning classes directory", e);
            }
        }

        private static void scanFromJar(Map<String, HandlerMethod> routes, Path jarPath, String packagePath)
                throws ReflectiveOperationException {
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                jarFile.stream()
                        .map(JarEntry::getName)
                        .filter(name -> name.startsWith(packagePath))
                        .filter(name -> name.endsWith(".class"))
                        .filter(name -> !name.contains("$"))
                        .map(name -> name.replace('/', '.').replaceAll("\\.class$", ""))
                        .forEach(className -> tryLoadController(routes, className));
            } catch (Exception e) {
                throw new ReflectiveOperationException("Error scanning executable JAR", e);
            }
        }

        private static void tryLoadController(Map<String, HandlerMethod> routes, String className) {
            try {
                Class<?> candidateClass = Class.forName(className);
                if (!candidateClass.isAnnotationPresent(RestController.class)) {
                    return;
                }
                routes.putAll(loadControllerClass(candidateClass));
            } catch (ReflectiveOperationException ignored) {
                // Continue scanning other classes.
            }
        }

        private static Map<String, HandlerMethod> loadControllerClass(Class<?> controllerClass) throws ReflectiveOperationException {
            if (!controllerClass.isAnnotationPresent(RestController.class)) {
                throw new IllegalArgumentException("Class is not annotated with @RestController: " + controllerClass.getName());
            }

            Object controllerInstance = controllerClass.getDeclaredConstructor().newInstance();
            Map<String, HandlerMethod> routes = new HashMap<>();

            for (Method method : controllerClass.getDeclaredMethods()) {
                GetMapping getMapping = method.getAnnotation(GetMapping.class);
                if (getMapping == null) {
                    continue;
                }

                if (method.getReturnType() != String.class) {
                    throw new IllegalArgumentException("Only methods with String return type are supported: " + method.getName());
                }

                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getType() != String.class || !parameter.isAnnotationPresent(RequestParam.class)) {
                        throw new IllegalArgumentException("Only String parameters with @RequestParam are supported: " + method.getName());
                    }
                }

                routes.put(getMapping.value(), new HandlerMethod(controllerInstance, method));
            }

            return routes;
        }

        private static String toClassName(Path rootPath, String basePackage, Path classFile) {
            Path relative = rootPath.relativize(classFile);
            String suffix = relative.toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
            return basePackage + "." + suffix;
        }
    }

    static class HandlerMethod {

        private final Object controller;
        private final Method method;

        HandlerMethod(Object controller, Method method) {
            this.controller = controller;
            this.method = method;
        }

        String invoke(Map<String, String> queryParams) throws InvocationTargetException, IllegalAccessException {
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);

                String value = queryParams.get(requestParam.value());
                if (value == null || value.isBlank()) {
                    value = requestParam.defaultValue();
                }
                args[i] = value;
            }

            return (String) method.invoke(controller, args);
        }
    }

    static class MicroHttpServer {

        private final int port;
        private final Map<String, HandlerMethod> getRoutes;

        MicroHttpServer(int port, Map<String, HandlerMethod> getRoutes) {
            this.port = port;
            this.getRoutes = getRoutes;
        }

        void start() throws IOException {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (true) {
                    try (Socket clientSocket = serverSocket.accept()) {
                        handleClient(clientSocket);
                    }
                }
            }
        }

        private void handleClient(Socket clientSocket) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();

            if (requestLine == null || requestLine.isBlank()) {
                writeResponse(clientSocket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String[] tokens = requestLine.split(" ");
            if (tokens.length < 3) {
                writeResponse(clientSocket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String method = tokens[0];
            if (!"GET".equals(method)) {
                writeResponse(clientSocket, "405 Method Not Allowed", "text/plain", "Method Not Allowed");
                return;
            }

            URI uri = URI.create(tokens[1]);
            String path = uri.getPath();
            Map<String, String> queryParams = parseQueryParams(uri.getRawQuery());

            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // Skip request headers in this minimal implementation.
            }

            HandlerMethod handler = getRoutes.get(path);
            if (handler != null) {
                try {
                    String responseBody = handler.invoke(queryParams);
                    writeResponse(clientSocket, "200 OK", "text/plain", responseBody == null ? "" : responseBody);
                } catch (Exception e) {
                    writeResponse(clientSocket, "500 Internal Server Error", "text/plain", "Internal Server Error");
                }
                return;
            }

            if (trySendStaticFile(clientSocket, path)) {
                return;
            }

            writeResponse(clientSocket, "404 Not Found", "text/plain", "Not Found");
        }

        private Map<String, String> parseQueryParams(String rawQuery) {
            Map<String, String> values = new HashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return values;
            }

            String[] pairs = rawQuery.split("&");
            for (String pair : pairs) {
                if (pair.isBlank()) {
                    continue;
                }

                String[] keyValue = pair.split("=", 2);
                String key = decode(keyValue[0]);
                String value = keyValue.length > 1 ? decode(keyValue[1]) : "";
                values.put(key, value);
            }

            return values;
        }

        private String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private boolean trySendStaticFile(Socket clientSocket, String path) throws IOException {
            String staticPath = path;
            if (staticPath == null || staticPath.isBlank() || "/".equals(staticPath)) {
                staticPath = "/index.html";
            }

            if (staticPath.contains("..")) {
                writeResponse(clientSocket, "403 Forbidden", "text/plain", "Forbidden");
                return true;
            }

            String resourcePath = "public" + (staticPath.startsWith("/") ? staticPath : "/" + staticPath);
            try (InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    return false;
                }

                byte[] bodyBytes = resourceStream.readAllBytes();
                writeBytesResponse(clientSocket, "200 OK", contentTypeFor(staticPath), bodyBytes);
                return true;
            }
        }

        private String contentTypeFor(String path) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                return "text/html; charset=UTF-8";
            }
            if (lower.endsWith(".png")) {
                return "image/png";
            }
            return "application/octet-stream";
        }

        private void writeResponse(Socket clientSocket, String status, String contentType, String body) throws IOException {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            writeBytesResponse(clientSocket, status, contentType + "; charset=UTF-8", bodyBytes);
        }

        private void writeBytesResponse(Socket clientSocket, String status, String contentType, byte[] bodyBytes) throws IOException {
            String response = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            OutputStream out = clientSocket.getOutputStream();
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        }
    }
}
