package com.github.blaszczyk.scopeviso.praemienservice.domain;

public record PraemienAntragRequest(int kilometerleistung, Fahrzeugtyp fahrzeugtyp, String bundesland, String kreis, String stadt, String postleitzahl, String bezirk) {
}
