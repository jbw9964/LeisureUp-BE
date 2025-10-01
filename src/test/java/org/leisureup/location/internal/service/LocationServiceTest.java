package org.leisureup.location.internal.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.*;
import com.github.tomakehurst.wiremock.matching.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.leisureup.global.exception.*;
import org.leisureup.location.internal.domain.*;
import org.leisureup.location.internal.dto.response.*;
import org.leisureup.location.internal.repository.*;
import org.mockito.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.cloud.contract.wiremock.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.util.*;

@SuppressWarnings("SpringBootApplicationProperties")
@SpringBootTest
@AutoConfigureWireMock(port = 0)
@TestPropertySource(properties = {
        "feign.tour-api.url=http://localhost:${wiremock.server.port}",
        "tourApi.key=testing"
})
class LocationServiceTest {

    private final static Long dbExistingLocationId = 10L;
    private final static Long apiExistingLocationId = 990140L;
    private final static Long apiNonExistentLocationId = 0L;

    @Value("${tourApi.key}")
    String apiKeyForTest;

    @Value("${tourApi.types.os}")
    String os;

    @Value("${tourApi.types.app}")
    String app;

    @Value("${tourApi.types.response}")
    String response;

    @Autowired
    WireMockServer wireMockServer;
    @Autowired
    LocationService locationService;
    @Autowired
    LocationRepository locationRepo;
    @Autowired
    CategoryRepository categoryRepo;
    @MockitoSpyBean
    LocationFetchService locationFetchService;


    // classpath 에서 파일을 읽어 byte 로 제공
    private static byte[] supplyResponse(Long locationId) {

        String classPath = String.format(
                "classpath:" +
                "mock-server-response/tour-api/get-common-detail" +
                "/%d.json", locationId
        );

        try {
            return Files.readAllBytes(
                    ResourceUtils.getFile(classPath).toPath()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // api 응답을 stub
    void stubMockServer(Long locationId) {

        byte[] body = supplyResponse(locationId);

        Map<String, StringValuePattern> queryParams = new HashMap<>();
        queryParams.put("serviceKey", equalTo(apiKeyForTest));
        queryParams.put("MobileApp", equalTo(app));
        queryParams.put("MobileOS", equalTo(os));
        queryParams.put("_type", equalTo(response));
        queryParams.put("contentId", equalTo(String.valueOf(locationId)));

        stubFor(
                get(urlPathEqualTo("/detailCommon2"))
                        .withQueryParams(queryParams)
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody(body)
                                        .withHeader(
                                                "Content-Type", "application/json"
                                        )
                        )
        );
    }

    @BeforeEach
    void setUp() {
        wireMockServer.start();

        stubMockServer(apiExistingLocationId);      // api 상 정보 있는 경우 stub
        stubMockServer(apiNonExistentLocationId);   // api 상 정보 없는 경우 stub

        // DB 에 정보 있는 경우 setup
        Category cat = CatOther.of("testing", "abcde");
        categoryRepo.save(cat);

        GpsCord cord = GpsCord.of(0.1234, 0.1234);
        Location loc = Location.of(
                dbExistingLocationId, 32L, "test", cord,
                null, null
        );
        loc.changeCategory(cat);
        loc.synchronizeTo(LocalDateTime.now());

        locationRepo.save(loc);
    }

    @AfterEach
    void tearDown() {
        locationRepo.deleteAll();
        categoryRepo.deleteAll();
        wireMockServer.resetAll();
        wireMockServer.stop();
    }


    @Test
    @DisplayName("DB 에 장소가 없을땐 API 로 가져와 저장한다.")
    void testGetLocation() {

        BasicLocationInfo resp = locationService.getLocation(apiExistingLocationId);

        // 정상 응답이 돌아온다.
        assertThat(resp).isNotNull().hasNoNullFieldsOrProperties();
        assertThat(resp.id()).isEqualTo(apiExistingLocationId);

        // db 에도 저장되어 있다.
        assertThat(locationRepo.findById(apiExistingLocationId)).isPresent();

    }

    @Test
    @DisplayName("API 에 장소가 없으면 NotFound 에러가 발생한다.")
    void testNonExistingLocation() {

        assertThatThrownBy(() -> locationService.getLocation(apiNonExistentLocationId))
                .isInstanceOf(NotFound.class);

    }

    @Test
    @DisplayName("DB 에 장소가 있을 땐 API 를 호출하지 않는다.")
    void testDbExistingLocation() {
        BasicLocationInfo resp = locationService.getLocation(dbExistingLocationId);

        // 정상 응답이 돌아온다.
        assertThat(resp).isNotNull().hasNoNullFieldsOrProperties();
        assertThat(resp.id()).isEqualTo(dbExistingLocationId);

        // Api 외부 호출한 적이 없다.
        Mockito.verify(locationFetchService, Mockito.times(0))
                .fetchAndStoreLocation(dbExistingLocationId);

    }

}