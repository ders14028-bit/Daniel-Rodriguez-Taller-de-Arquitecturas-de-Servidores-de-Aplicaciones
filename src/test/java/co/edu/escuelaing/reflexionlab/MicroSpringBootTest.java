package co.edu.escuelaing.reflexionlab;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MicroSpringBootTest {

    @Test
    void loadAllDiscoversControllerRoutes() throws ReflectiveOperationException {
        Map<String, MicroSpringBoot.HandlerMethod> routes =
                MicroSpringBoot.ControllerLoader.loadAll("co.edu.escuelaing.reflexionlab");

        assertTrue(routes.containsKey("/"));
        assertTrue(routes.containsKey("/greeting"));
    }

    @Test
    void greetingRouteUsesDefaultValueWhenMissing() throws ReflectiveOperationException, InvocationTargetException, IllegalAccessException {
        Map<String, MicroSpringBoot.HandlerMethod> routes =
                MicroSpringBoot.ControllerLoader.load("co.edu.escuelaing.reflexionlab.GreetingController");

        String response = routes.get("/greeting").invoke(new HashMap<>());

        assertEquals("Hola World", response);
    }

    @Test
    void greetingRouteUsesProvidedQueryParameter() throws ReflectiveOperationException, InvocationTargetException, IllegalAccessException {
        Map<String, MicroSpringBoot.HandlerMethod> routes =
                MicroSpringBoot.ControllerLoader.load("co.edu.escuelaing.reflexionlab.GreetingController");

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("name", "Daniel");

        String response = routes.get("/greeting").invoke(queryParams);

        assertEquals("Hola Daniel", response);
    }

    @Test
    void loadRejectsClassesWithoutRestControllerAnnotation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MicroSpringBoot.ControllerLoader.load("java.lang.String"));
    }
}
