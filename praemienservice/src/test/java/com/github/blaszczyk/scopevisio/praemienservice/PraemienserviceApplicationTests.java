package com.github.blaszczyk.scopevisio.praemienservice;

import com.github.blaszczyk.scopevisio.praemienservice.domain.*;
import com.github.blaszczyk.scopevisio.praemienservice.persistence.PraemienAntragEntity;
import com.github.blaszczyk.scopevisio.praemienservice.persistence.PraemienAntragRepository;
import com.github.blaszczyk.scopevisio.praemienservice.util.MockPostcodeService;
import com.github.blaszczyk.scopevisio.praemienservice.util.DatabaseContainer;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
		registry.add("postcode-service.port", mockPostcodeService::port);
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

	@Autowired
	private PraemienAntragRepository repository;

	private static final String PATH_POST_ANTRAG = "/antrag";

	private static final String PATH_GET_SUMMARY = "/summary/{id}";

	private static final Location SWISTTAL = new Location("Nordrhein-Westfalen", "Rhein-Sieg-Kreis", "Swisttal", "53913", "Morenhoven");

	@Test
	void contextLoads() {
	}

	@Test
	void post_antrag_requests_returns_200_with_response() {

		final int kilometerleistung = 14000;
		final Fahrzeugtyp fahrzeugtyp = Fahrzeugtyp.PKW;
		final int expectedPraemie = 750;

		mockPostcodeService.createGetLocationsExpectation(SWISTTAL.postleitzahl(), 200, List.of(SWISTTAL));

		final var request = new PraemienAntragRequest(kilometerleistung, fahrzeugtyp, SWISTTAL);

		final var documentation = document("post_antrag",
				preprocessRequest(prettyPrint()),
				preprocessResponse(prettyPrint()),
				new RequestBodySnippet(),
				new ResponseBodySnippet(),
				requestFields(ANTRAG_REQUEST_FIELDS),
				responseFields(ANTRAG_RESPONSE_FIELDS));

		final PraemienAntragResponse praemienAntragResponse =
				given(this.spec)
					.body(request)
					.header("content-type", ContentType.JSON)
					.filter(documentation)
				.when()
					.post(PATH_POST_ANTRAG)
				.then()
					.statusCode(200)
					.extract()
					.jsonPath()
					.getObject(".", PraemienAntragResponse.class);

		assertEquals(expectedPraemie, praemienAntragResponse.praemie());

		final UUID id = praemienAntragResponse.id();

		final PraemienAntragEntity persistedAntrag = repository.findById(id).block();

		final var expectedAntrag = createPraemienAntrag(id, fahrzeugtyp, kilometerleistung, expectedPraemie, SWISTTAL);
		assertEquals(expectedAntrag, persistedAntrag);
	}

	private static Location locationWithPlz(final String postleitzahl) {
		return new Location("Nordrhein-Westfalen", "Bonn", "Bonn", postleitzahl, "Endenich");
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
				Arguments.of(1000, Fahrzeugtyp.PKW, null),
				Arguments.of(1000, Fahrzeugtyp.PKW, locationWithPlz(null)),
				Arguments.of(1000, Fahrzeugtyp.PKW, locationWithPlz("notAPostCode")),
				Arguments.of(1000, null, locationWithPlz("53121")),
				Arguments.of(0, Fahrzeugtyp.PKW, locationWithPlz("53121")),
				Arguments.of(-1000, Fahrzeugtyp.PKW, locationWithPlz("53121"))
		);
	}

	@ParameterizedTest
	@MethodSource("invalidRequests")
	void post_antrag_requests_returns_400_for_invalid_request(final int kilometerleistung, final Fahrzeugtyp fahrzeugtyp, final Location ort) {

		final var request = new PraemienAntragRequest(kilometerleistung, fahrzeugtyp, ort);

		given(this.spec)
			.body(request)
			.header("content-type", ContentType.JSON)
		.when()
			.post(PATH_POST_ANTRAG)
		.then()
			.statusCode(400);
	}

	@Test
	void post_antrag_requests_returns_400_for_unknown_location() {

		mockPostcodeService.createGetLocationsExpectation("53913", 200, List.of(SWISTTAL));

		final Location unknownLocation = new Location(SWISTTAL.bundesland(),
				SWISTTAL.kreis(), "Nicht Swisttal", SWISTTAL.postleitzahl(), SWISTTAL.bezirk());
		final var request = new PraemienAntragRequest(14000, Fahrzeugtyp.PKW, unknownLocation);

		given(this.spec)
			.body(request)
			.header("content-type", ContentType.JSON)
		.when()
			.post(PATH_POST_ANTRAG)
		.then()
			.statusCode(400);
	}

	@Test
	void get_antrag_summary_returns_200_with_response() {
		final int kilometerleistung = 9000;
		final Fahrzeugtyp fahrzeugtyp = Fahrzeugtyp.ZWEIRAD;
		final int expectedPraemie = 1000;

		final var documentation = document("get_summary",
				preprocessResponse(prettyPrint()),
				pathParameters(parameterWithName("id").description("Id")),
				new ResponseBodySnippet(),
				responseFields(ANTRAG_SUMMARY_FIELDS)
		);

		final var persistedAntrag = createPraemienAntrag(null, fahrzeugtyp, kilometerleistung, expectedPraemie, SWISTTAL);

		final UUID id = repository.save(persistedAntrag).block().getId();

		final PraemienAntragSummary praemienAntragSummary =
				given(this.spec)
						.filter(documentation)
				.when()
						.get(PATH_GET_SUMMARY, id)
				.then()
						.statusCode(200)
						.extract()
						.jsonPath()
						.getObject(".", PraemienAntragSummary.class);
		final var expectedSummary = new PraemienAntragSummary(id, expectedPraemie, kilometerleistung, fahrzeugtyp,
				SWISTTAL);
		assertEquals(expectedSummary, praemienAntragSummary);
	}

	@Test
	void get_antrag_summary_returns_400_with_for_unknown_id() {

		final UUID unknownId = UUID.randomUUID();

		given(this.spec)
			.when()
				.get(PATH_GET_SUMMARY, unknownId)
			.then()
				.statusCode(400);
	}

	private static final List<FieldDescriptor> ANTRAG_REQUEST_FIELDS = List.of(
			fieldWithPath("kilometerleistung").description("Milage, must be positive"),
			fieldWithPath("fahrzeugtyp").description("Type of Vehicle, values are " + Arrays.toString(Fahrzeugtyp.values())),
			fieldWithPath("ort").description("Location"),
			fieldWithPath("ort.bundesland").description("Federal State"),
			fieldWithPath("ort.kreis").description("Municipality"),
			fieldWithPath("ort.stadt").description("City"),
			fieldWithPath("ort.postleitzahl").description("Postcode"),
			fieldWithPath("ort.bezirk").optional().description("District, optional")
	);

	private static final List<FieldDescriptor> ANTRAG_RESPONSE_FIELDS = List.of(
			fieldWithPath("id").description("Generated Id"),
			fieldWithPath("praemie").description("Calculated premium in Euro")
	);

	private static final List<FieldDescriptor> ANTRAG_SUMMARY_FIELDS = Stream.concat(
			ANTRAG_RESPONSE_FIELDS.stream(),
			ANTRAG_REQUEST_FIELDS.stream()
	).toList();

	private static PraemienAntragEntity createPraemienAntrag(final UUID id, final Fahrzeugtyp fahrzeugtyp,
															  final int kilometerleistung, final int praemie,
															  final Location location) {
		final var result = new PraemienAntragEntity();
		result.setId(id);
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
