package com.jannetai.backend.client.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of Google responses (shapes from the Places API (New) and Geocoding API documentation). */
class GoogleMapsClientParseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void geocodeTakesDistrictFromLevel3AndPinFromALaterResult() throws Exception {
        String body = """
                {"status":"OK","results":[
                  {"formatted_address":"Republic Crossing, Raj Nagar Extension, Ghaziabad, Uttar Pradesh, India",
                   "geometry":{"location":{"lat":28.7001,"lng":77.4302}},
                   "address_components":[
                     {"long_name":"Republic Crossing","types":["point_of_interest","establishment"]},
                     {"long_name":"Raj Nagar Extension","types":["sublocality_level_1","sublocality","political"]},
                     {"long_name":"Ghaziabad","types":["locality","political"]},
                     {"long_name":"Meerut Division","types":["administrative_area_level_2","political"]},
                     {"long_name":"Ghaziabad","types":["administrative_area_level_3","political"]},
                     {"long_name":"Uttar Pradesh","types":["administrative_area_level_1","political"]}]},
                  {"formatted_address":"Uttar Pradesh 201017, India","geometry":{"location":{"lat":28.7,"lng":77.43}},
                   "address_components":[{"long_name":"201017","types":["postal_code"]}]}]}
                """;
        GoogleAddress a = GoogleMapsClient.parseGeocode(json.readTree(body));
        assertThat(a.pincode()).isEqualTo("201017");
        assertThat(a.stateName()).isEqualTo("Uttar Pradesh");
        assertThat(a.districtNames()).containsExactly("Ghaziabad", "Meerut Division");
        assertThat(a.localityNames()).containsExactly("Raj Nagar Extension", "Ghaziabad");
        assertThat(a.placeName()).isEqualTo("Republic Crossing");
        assertThat(a.latitude()).isEqualTo(28.7001);
    }

    @Test
    void placeDetailsUsesCamelCaseComponents() throws Exception {
        String body = """
                {"id":"ChIJxyz","formattedAddress":"Jolah Bigha, Bihar 824236, India",
                 "location":{"latitude":24.94,"longitude":84.84},
                 "addressComponents":[
                   {"longText":"Jolah Bigha","types":["locality","political"]},
                   {"longText":"Gaya","types":["administrative_area_level_3","political"]},
                   {"longText":"Bihar","types":["administrative_area_level_1","political"]},
                   {"longText":"824236","types":["postal_code"]}]}
                """;
        GoogleAddress a = GoogleMapsClient.parsePlace(json.readTree(body));
        assertThat(a.localityNames()).containsExactly("Jolah Bigha");
        assertThat(a.districtNames()).containsExactly("Gaya");
        assertThat(a.pincode()).isEqualTo("824236");
    }

    @Test
    void autocompleteKeepsOnlyPlacePredictions() throws Exception {
        String body = """
                {"suggestions":[
                  {"placePrediction":{"placeId":"ChIJabc","text":{"text":"Republic Crossing, Ghaziabad"},
                    "structuredFormat":{"mainText":{"text":"Republic Crossing"},"secondaryText":{"text":"Raj Nagar Extension, Ghaziabad"}}}},
                  {"queryPrediction":{"text":{"text":"republic crossing near me"}}}]}
                """;
        List<PlaceSuggestion> s = GoogleMapsClient.parseAutocomplete(json.readTree(body));
        assertThat(s).containsExactly(new PlaceSuggestion("ChIJabc", "Republic Crossing", "Raj Nagar Extension, Ghaziabad"));
    }

    @Test
    void zeroResultsGivesNull() throws Exception {
        assertThat(GoogleMapsClient.parseGeocode(json.readTree("{\"status\":\"ZERO_RESULTS\",\"results\":[]}"))).isNull();
    }
}
