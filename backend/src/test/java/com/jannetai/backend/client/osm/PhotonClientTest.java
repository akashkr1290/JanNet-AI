package com.jannetai.backend.client.osm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link PhotonClient} without any network: parsing, India-only, the off switch, a failing server. */
class PhotonClientTest {

    private final ObjectMapper json = new ObjectMapper();

    private PhotonClient client(boolean enabled) {
        return new PhotonClient(enabled, "http://127.0.0.1:1", "JanNetAI-test/1.0", 500, new RestTemplateBuilder());
    }

    @Test
    void parsesIndianPlacesWithNameAreaAndPosition() throws Exception {
        List<NominatimClient.OsmPlace> places = PhotonClient.parseSearch(json.readTree("""
                {"features":[
                 {"geometry":{"coordinates":[77.4302,28.7001],"type":"Point"},
                  "properties":{"name":"Crossings Republik","district":"Raj Nagar Extension","city":"Ghaziabad",
                                "state":"Uttar Pradesh","country":"India","countrycode":"IN"}},
                 {"geometry":{"coordinates":[67.0,24.8],"type":"Point"},
                  "properties":{"name":"Crossings Republik","city":"Karachi","country":"Pakistan","countrycode":"PK"}},
                 {"geometry":{"coordinates":[77.0,28.0],"type":"Point"},
                  "properties":{"country":"India","countrycode":"IN"}},
                 {"geometry":{"type":"Point"},"properties":{"name":"No position","countrycode":"IN"}}
                ]}
                """));
        assertThat(places).hasSize(1);
        NominatimClient.OsmPlace p = places.get(0);
        assertThat(p.name()).isEqualTo("Crossings Republik");
        assertThat(p.displayName()).isEqualTo("Raj Nagar Extension, Ghaziabad, Uttar Pradesh");
        assertThat(p.latitude()).isEqualTo(28.7001);
        assertThat(p.longitude()).isEqualTo(77.4302);
    }

    @Test
    void aStreetOnlyResultUsesTheStreetAsItsName() throws Exception {
        List<NominatimClient.OsmPlace> places = PhotonClient.parseSearch(json.readTree("""
                {"features":[{"geometry":{"coordinates":[77.4,28.6],"type":"Point"},
                  "properties":{"street":"NH 9","city":"Ghaziabad","countrycode":"IN"}}]}
                """));
        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("NH 9");
        assertThat(places.get(0).displayName()).isEqualTo("Ghaziabad");
    }

    @Test
    void anAnswerWithoutFeaturesIsEmpty() throws Exception {
        assertThat(PhotonClient.parseSearch(json.readTree("{\"error\":\"x\"}"))).isEmpty();
        assertThat(PhotonClient.parseSearch(null)).isEmpty();
    }

    @Test
    void switchedOffClientAnswersNothing() {
        PhotonClient off = client(false);
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.search("Republic Crossing", 28.7, 77.4)).isEmpty();
    }

    @Test
    void unreachableServerGivesAnEmptyAnswerNotAnException() {
        assertThat(client(true).search("Republic Crossing", null, null)).isEmpty();
        assertThat(client(true).search("   ", null, null)).isEmpty();
    }
}
