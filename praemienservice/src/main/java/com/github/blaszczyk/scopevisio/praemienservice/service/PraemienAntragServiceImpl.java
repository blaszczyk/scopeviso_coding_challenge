package com.github.blaszczyk.scopevisio.praemienservice.service;

import com.github.blaszczyk.scopevisio.praemienservice.bi.PraemienCalculator;
import com.github.blaszczyk.scopevisio.praemienservice.domain.PraemienAntragResponse;
import com.github.blaszczyk.scopevisio.praemienservice.persistence.PraemienAntragRepository;
import com.github.blaszczyk.scopevisio.praemienservice.persistence.PraemienAntragTransformer;
import com.github.blaszczyk.scopevisio.praemienservice.client.PostcodeClient;
import com.github.blaszczyk.scopevisio.praemienservice.domain.PraemienAntragRequest;
import com.github.blaszczyk.scopevisio.praemienservice.domain.PraemienAntragRequestValidator;
import com.github.blaszczyk.scopevisio.praemienservice.domain.PraemienAntragSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
public class PraemienAntragServiceImpl implements PraemienAntragService {

    @Autowired
    private PostcodeClient postcodeClient;

    @Autowired
    private PraemienAntragRepository praemienAntragRepository;
    
    @Override
    public Mono<ResponseEntity<PraemienAntragResponse>> postPraemienAntrag(PraemienAntragRequest antrag) {
        if(PraemienAntragRequestValidator.isRequestValid(antrag)) {
            return postcodeClient.getLocations(antrag.ort().postleitzahl())
                    .doOnNext(validLocations -> PraemienAntragRequestValidator.validateLocation(antrag.ort(), validLocations))
                    .map(ignore -> PraemienCalculator.calculate(antrag))
                    .map(praemie -> PraemienAntragTransformer.transformToEntity(antrag, praemie))
                    .flatMap(praemienAntragRepository::save)
                    .map(PraemienAntragTransformer::transformToResponse)
                    .map(ResponseEntity::ok)
                    .onErrorResume(this::handleError);
        }
        else {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @Override
    public Mono<ResponseEntity<PraemienAntragSummary>> getSummary(UUID id) {
        return praemienAntragRepository.findById(id)
                .map(PraemienAntragTransformer::transformToSummary)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.badRequest().build()));
    }

    private <T> Mono<ResponseEntity<T>> handleError(Throwable error) {
        final HttpStatusCode status;
        if (error instanceof ResponseStatusException responseStatusException) {
            status = responseStatusException.getStatusCode();
        }
        else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return Mono.just(ResponseEntity.status(status).build());
    }

}
