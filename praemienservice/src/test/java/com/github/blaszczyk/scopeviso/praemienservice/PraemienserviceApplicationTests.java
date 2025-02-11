package com.github.blaszczyk.scopeviso.praemienservice;

import com.github.blaszczyk.scopeviso.praemienservice.domain.*;
import com.github.blaszczyk.scopeviso.praemienservice.persistence.PraemienAnfrage;
import com.github.blaszczyk.scopeviso.praemienservice.persistence.PraemienAnfrageRepository;
import com.github.blaszczyk.scopeviso.praemienservice.util.DatabaseContainer;
import com.github.blaszczyk.scopeviso.praemienservice.util.MockPostcodeService;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.RequestBodySnippet;
import org.springframework.restdocs.payload.ResponseBodySnippet;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Container;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.*;
import static org.springframework.restdocs.restassured.RestAssuredRestDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;

@ExtendWith({RestDocumentationExtension.class, SpringExtension.class})
@SpringBootTest(classes = { PraemienServiceApplication.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PraemienserviceApplicationTests {

	@Container
	private static final DatabaseContainer database = new DatabaseContainer();

	private static final MockPostcodeService mockPostcodeService = new MockPostcodeService();

	@DynamicPropertySource
	static void databaseProperties (DynamicPropertyRegistry registry) {
		database.start();
		registry.add("database.host", database::getHost);
		registry.add("database.port", database::getFirstMappedPort);
		registry.add("database.username", database::getUsername);
		registry.add("database.password", database::getPassword);
		registry.add("database.name", database::getDatabaseName);
		assertTrue(database.isRunning());
	}

	@DynamicPropertySource
	static void postcodeServiceProperties (DynamicPropertyRegistry registry) {
		registry.add("postcodeservice.port", () -> MockPostcodeService.PORT);
	}

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startMockService() {
		mockPostcodeService.start();
	}

	@BeforeEach
	void clearExpectations() {
		mockPostcodeService.resetAll();
	}

	@AfterEach
	void checkUnmatchedRequests() {
		mockPostcodeService.checkForUnmatchedRequests();
	}

	@AfterAll
	static void stopMockService() {
		mockPostcodeService.stop();
	}

	private RequestSpecification spec;

	@BeforeEach
	void setDocumentationConfiguration(RestDocumentationContextProvider restDocumentation) {
		this.spec = new RequestSpecBuilder()
				.setBaseUri("http://localhost:" + port)
				.setBasePath("/praemien/api")
				.setAccept(ContentType.JSON)
				.addFilter(documentationConfiguration(restDocumentation))
				.build();
	}

	@Test
	void contextLoads() {
	}

	@Autowired
	private PraemienAnfrageRepository repository;

	private static final Location SWISTTAL = new Location("Nordrhein-Westfalen", "Rhein-Sieg-Kreis", "Swisttal", "53913", null);

	@Test
	void post_anfrage_requests_returns_200_with_response() {

		final int kilometerleistung = 14000;
		final Fahrzeugtyp fahrzeugtyp = Fahrzeugtyp.PKW;
		final int expectedPraemie = 750;

		mockPostcodeService.createGetLocationsExpectation("53913", 200, List.of(SWISTTAL));

		final var request = new PraemienAnfrageRequest(kilometerleistung, fahrzeugtyp, SWISTTAL.bundesland(),
				SWISTTAL.kreis(), SWISTTAL.stadt(), SWISTTAL.postleitzahl(), SWISTTAL.bezirk());

		final var documentation = document("post_anfrage",
				preprocessRequest(prettyPrint()),
				preprocessResponse(prettyPrint()),
				new RequestBodySnippet(),
				new ResponseBodySnippet(),
				requestFields(ANFRAGE_REQUEST_FIELDS),
				responseFields(ANFRAGE_RESPONSE_FIELDS));

		final PraemienAnfrageResponse praemienAnfrageResponse =
				given(this.spec)
					.body(request)
					.header("content-type", ContentType.JSON)
					.filter(documentation)
				.when()
					.post("/anfrage")
				.then()
					.statusCode(200)
					.extract()
					.jsonPath()
					.getObject(".", PraemienAnfrageResponse.class);

		assertEquals(expectedPraemie, praemienAnfrageResponse.praemie());

		final UUID id = praemienAnfrageResponse.id();

		final PraemienAnfrage persistedAnfrage = repository.findByPraemienId(id).block();

		final var expectedAnfrage = createPraemienAnfrage(id, fahrzeugtyp, kilometerleistung, expectedPraemie, SWISTTAL);
		assertEquals(expectedAnfrage, persistedAnfrage);
	}

	@Test
	void post_anfrage_requests_returns_400_for_unknown_location() {

		mockPostcodeService.createGetLocationsExpectation("53913", 200, List.of(SWISTTAL));

		final var request = new PraemienAnfrageRequest(14000, Fahrzeugtyp.PKW, SWISTTAL.bundesland(),
				SWISTTAL.kreis(), "Nicht Swisttal", SWISTTAL.postleitzahl(), SWISTTAL.bezirk());

		given(this.spec)
			.body(request)
			.header("content-type", ContentType.JSON)
		.when()
			.post("/anfrage")
		.then()
			.statusCode(400);
	}

	@Test
	void get_anfrage_summary_returns_200_with_response() {
		final int kilometerleistung = 9000;
		final Fahrzeugtyp fahrzeugtyp = Fahrzeugtyp.ZWEIRAD;
		final int expectedPraemie = 1000;

		final var documentation = document("get_summary",
				preprocessResponse(prettyPrint()),
				pathParameters(parameterWithName("id").description("Id der Prämienanfrage")),
				new ResponseBodySnippet(),
				responseFields(ANFRAGE_SUMMARY_FIELDS)
		);

		final UUID id = UUID.randomUUID();

		final var persistedAnfrage = createPraemienAnfrage(id, fahrzeugtyp, kilometerleistung, expectedPraemie, SWISTTAL);
		repository.save(persistedAnfrage).block();

		final PraemienAnfrageSummary praemienAnfrageSummary =
				given(this.spec)
						.filter(documentation)
				.when()
						.get("/summary/{id}", id)
				.then()
						.statusCode(200)
						.extract()
						.jsonPath()
						.getObject(".", PraemienAnfrageSummary.class);
		final var expectedSummary = new PraemienAnfrageSummary(id, expectedPraemie, kilometerleistung, fahrzeugtyp,
				SWISTTAL.bundesland(), SWISTTAL.kreis(), SWISTTAL.stadt(), SWISTTAL.postleitzahl(), SWISTTAL.bezirk());
		assertEquals(expectedSummary, praemienAnfrageSummary);
	}

	private static final List<FieldDescriptor> ANFRAGE_REQUEST_FIELDS = List.of(
			fieldWithPath("kilometerleistung").description("Geschätzte Kilometerleistung"),
			fieldWithPath("fahrzeugtyp").description("Gewählter Fahrzeugtyp, gülttige Werte: " + Arrays.toString(Fahrzeugtyp.values())),
			fieldWithPath("bundesland").description("Bundesland des Antragstellers"),
			fieldWithPath("kreis").description("Kreis des Antragstellers"),
			fieldWithPath("stadt").description("Stadt/Gemeinde des Antragstellers"),
			fieldWithPath("postleitzahl").description("Postleitzahl des Antragstellers"),
			fieldWithPath("bezirk").optional().description("Bezirk des Antragstellers, optional")
	);

	private static final List<FieldDescriptor> ANFRAGE_RESPONSE_FIELDS = List.of(
			fieldWithPath("id").description("Generierte Id"),
			fieldWithPath("praemie").description("Berechnete Prämie in Euro")
	);

	private static final List<FieldDescriptor> ANFRAGE_SUMMARY_FIELDS = Stream.concat(
			ANFRAGE_RESPONSE_FIELDS.stream(),
			ANFRAGE_REQUEST_FIELDS.stream()
	).toList();

	private static PraemienAnfrage createPraemienAnfrage(final UUID id, final Fahrzeugtyp fahrzeugtyp,
												  final int kilometerleistung, final int praemie,
												  final Location location) {
		final var result = new PraemienAnfrage();
		result.setPraemienId(id);
		result.setPraemie(praemie);
		result.setKilometerleistung(kilometerleistung);
		result.setFahrzeugtyp(fahrzeugtyp);
		result.setBundesland(location.bundesland());
		result.setKreis(location.kreis());
		result.setStadt(location.stadt());
		result.setPostleitzahl(location.postleitzahl());
		result.setBezirk(location.bezirk());
		return result;
	}
}
