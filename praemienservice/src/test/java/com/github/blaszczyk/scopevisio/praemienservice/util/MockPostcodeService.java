package com.github.blaszczyk.scopevisio.praemienservice.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.blaszczyk.scopevisio.praemienservice.domain.Location;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.http.MediaType;
import org.springframework.test.util.TestSocketUtils;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class MockPostcodeService extends WireMockServer {

    public MockPostcodeService() {
        super(TestSocketUtils.findAvailableTcpPort());
    }

    public void createGetLocationsExpectation(final String postleitzahl, final int statusCode, final List<Location> response) {
        stubFor(get(urlEqualTo("/postcode/api/location/" + postleitzahl))
                .willReturn(aResponse()
                        .withStatus(statusCode)
                        .withHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                        .withJsonBody(new ObjectMapper().valueToTree(response))
                )
        );
    }
}
