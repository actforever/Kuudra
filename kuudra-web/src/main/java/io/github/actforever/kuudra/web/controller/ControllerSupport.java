package io.github.actforever.kuudra.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Callable;

final class ControllerSupport {
    private ControllerSupport() {
    }

    static <T> T call(Callable<T> call, String type, String id) {
        try {
            return call.call();
        } catch (IllegalArgumentException error) {
            throw notFound(type, id);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    static ResponseStatusException notFound(String type, String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
    }
}
