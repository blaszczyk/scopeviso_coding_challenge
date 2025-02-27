package com.github.blaszczyk.scopeviso.praemienservice.domain;

import com.github.blaszczyk.scopeviso.praemienservice.exception.UnknownLocationException;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class PraemienAntragRequestValidator {

    private static final Pattern PLZ_PATTERN = Pattern.compile("\\d{5}");

    public static boolean isRequestValid(PraemienAntragRequest request) {
        return request.ort().postleitzahl() != null
                && PLZ_PATTERN.matcher(request.ort().postleitzahl()).matches()
                && request.kilometerleistung() > 0
                && request.fahrzeugtyp() != null;
    }

    public static Consumer<List<Location>> validateLocation(Location location) {
       return locations -> {
            if (!locations.contains(location)){
                throw new UnknownLocationException(location);
            }
        };
    }

    private PraemienAntragRequestValidator() {}
}
