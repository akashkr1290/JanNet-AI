package com.jannetai.backend.client.osm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NominatimClient} without any network: parsing of the documented
 * Nominatim JSON shapes, the "off" switch, the 1-request-per-second rule and
 * that a failing server never breaks the caller (the app then works from our
 * own dataset).
 */
class NominatimClientTest {

    private final ObjectMapper json = new ObjectMapper();

    private NominatimClient client(boolean enabled, String baseUrl) {
        return new NominatimClient(enabled, baseUrl, "", "JanNetAI-test/1.0", 500, new RestTemplateBuilder());
    }

    @Test
    void reverseAnswerGivesRoadColonyAndPlaceNamesMostSpecificFirst() throws Exception {
        NominatimClient.OsmAddress a = NominatimClient.parseReverse(json.readTree("""
                {"display_name":"Republic Crossing, Raj Nagar Extension, Ghaziabad, Uttar Pradesh, 201017, India",
                 "name":"Republic Crossing",
                 "address":{"road":"NH 9","neighbourhood":"Crossings Republik","suburb":"Raj Nagar Extension",
                            "postcode":"201017"}}
                """));
        assertThat(a.postcode()).isEqualTo("201017");
        assertThat(a.localityNames()).containsExactly("Republic Crossing", "Crossings Republik", "Raj Nagar Extension", "NH 9");
    }

    @Test
    void reverseErrorAnswerIsNoAddress() throws Exception {
        assertThat(NominatimClient.parseReverse(json.readTree("{\"error\":\"Unable to geocode\"}"))).isNull();
        assertThat(NominatimClient.parseReverse(null)).isNull();
    }

    @Test
    void searchKeepsOnlyResultsWithAPositionAndUsesTheFirstPartOfTheAddressAsName() throws Exception {
        List<NominatimClient.OsmPlace> places = NominatimClient.parseSearch(json.readTree("""
                [{"lat":"28.7001","lon":"77.4302","name":"Republic Crossing","display_name":"Republic Crossing, Ghaziabad"},
                 {"lat":"28.65","lon":"77.37","display_name":"Vaishali Metro Station, Ghaziabad"},
                 {"lat":"not a number","lon":"77.0","name":"Broken"}]
                """));
        assertThat(places).hasSize(2);
        assertThat(places.get(0).name()).isEqualTo("Republic Crossing");
        assertThat(places.get(0).latitude()).isEqualTo(28.7001);
        assertThat(places.get(1).name()).isEqualTo("Vaishali Metro Station");
    }

    @Test
    void searchAnswerThatIsNotAListIsEmpty() throws Exception {
        assertThat(NominatimClient.parseSearch(json.readTree("{\"error\":\"x\"}"))).isEmpty();
        assertThat(NominatimClient.parseSearch(null)).isEmpty();
    }

    @Test
    void switchedOffClientAnswersNothingAndDoesNotCallOut() {
        NominatimClient off = client(false, "http://127.0.0.1:1");
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.search("Republic Crossing", 28.7, 77.4)).isEmpty();
        assertThat(off.reverse(28.7, 77.4)).isEmpty();
    }

    @Test
    void onlyOneRequestPerSecondIsAllowed() {
        NominatimClient c = client(true, "http://127.0.0.1:1");
        assertThat(c.acquireSlot()).isTrue();
        assertThat(c.acquireSlot()).isFalse(); // a second request inside the same second gets no slot
    }

    @Test
    void unreachableServerGivesAnEmptyAnswerNotAnException() {
        NominatimClient c = client(true, "http://127.0.0.1:1"); // nothing listens on port 1
        assertThat(c.search("Republic Crossing", null, null)).isEmpty();
        assertThat(c.reverse(28.7, 77.4)).isEmpty();
    }

    @Test
    void blankSearchTextIsIgnored() {
        assertThat(client(true, "http://127.0.0.1:1").search("   ", null, null)).isEmpty();
    }

    @Test
    void aSearchWaitsForItsTurnInsteadOfGivingUp() {
        NominatimClient c = client(true, "http://127.0.0.1:1");
        assertThat(c.acquireSlot()).isTrue();
        assertThat(c.awaitSlot(0)).isFalse();     // no patience: no slot
        assertThat(c.awaitSlot(1_500)).isTrue();  // waits for the next free second
    }
}
