package uz.platform.keycloak.oneid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("OneIdUserInfo: parsing the identify response as the specification shows it")
class OneIdUserInfoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Field for field the specification's identify response. Every value is invented. */
    private static final String SPECIFICATION_SHAPE = """
            {
              "valid": "true",
              "validation_method": ["PKCSMETHOD"],
              "pin": "30101199012345",
              "user_id": "akarimov",
              "full_name": "KARIMOV ALI VALIYEVICH",
              "pport_no": "AA1234567",
              "birth_date": "1990-01-01",
              "sur_name": "KARIMOV",
              "first_name": "ALI",
              "mid_name": "VALIYEVICH",
              "user_type": "I",
              "sess_id": "5f0c1f7e-8d2a-4b1c-9f00-000000000001",
              "ret_cd": "0",
              "auth_method": "PKCSMETHOD",
              "legal_info": [
                {"is_basic": true,  "tin": "111111111", "acron_UZ": "IT-GROUP", "le_tin": "111111111", "le_name": "IT-GROUP MCHJ"},
                {"is_basic": false, "tin": "222222222", "acron_UZ": "QURILISH", "le_tin": "222222222", "le_name": "QURILISH SAVDO MCHJ"}
              ]
            }
            """;

    @Test
    @DisplayName("reads every field the platform uses")
    void readsTheSpecificationShape() throws Exception {
        OneIdUserInfo user = parse(SPECIFICATION_SHAPE);

        assertThat(user.successful()).isTrue();
        assertThat(user.valid()).isTrue();
        assertThat(user.pin()).isEqualTo("30101199012345");
        assertThat(user.userId()).isEqualTo("akarimov");
        assertThat(user.firstName()).isEqualTo("ALI");
        assertThat(user.surName()).isEqualTo("KARIMOV");
        assertThat(user.userType()).isEqualTo("I");
        assertThat(user.sessionId()).isEqualTo("5f0c1f7e-8d2a-4b1c-9f00-000000000001");
        assertThat(user.legalEntityTins()).containsExactly("111111111", "222222222");
        assertThat(user.legalEntities().get(0).basic()).isTrue();
        assertThat(user.pkcsLegalTin()).as("sent only for LEPKCSMETHOD").isNull();
    }

    @ParameterizedTest(name = "\"valid\": {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "\"true\"  | true",
            "true      | true",
            "\"false\" | false",
            "false     | false",
            "null      | false"})
    @DisplayName("valid is read as the string the specification sends, or a boolean, and absent means false")
    void validAcceptsStringOrBoolean(String json, boolean expected) throws Exception {
        assertThat(parse("{\"ret_cd\":\"0\",\"valid\":" + json + "}").valid()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"ret_cd\":\"1\",\"pin\":\"30101199012345\"}",
            "{\"pin\":\"30101199012345\"}"})
    @DisplayName("only ret_cd \"0\" is a success; a missing ret_cd is not")
    void onlyRetCdZeroIsSuccess(String json) throws Exception {
        assertThat(parse(json).successful()).isFalse();
    }

    @Test
    @DisplayName("a legal entity with only le_tin still yields its TIN; blank TINs are dropped")
    void leTinFallbackAndBlankTins() throws Exception {
        OneIdUserInfo user = parse("""
                {"ret_cd":"0","legal_info":[
                  {"le_tin":"444444444","le_name":"ONLY LE FIELDS"},
                  {"tin":"","le_tin":""}
                ]}""");

        assertThat(user.legalEntityTins()).containsExactly("444444444");
    }

    @Test
    @DisplayName("pkcs_legal_tin is read when a legal-entity e-signature was used")
    void pkcsLegalTin() throws Exception {
        OneIdUserInfo user = parse("""
                {"ret_cd":"0","user_type":"L","auth_method":"LEPKCSMETHOD","pkcs_legal_tin":"333333333"}""");

        assertThat(user.pkcsLegalTin()).isEqualTo("333333333");
    }

    @Test
    @DisplayName("no legal_info, or one that is not an array, means no legal entities rather than an error")
    void missingOrMalformedLegalInfo() throws Exception {
        assertThat(parse("{\"ret_cd\":\"0\"}").legalEntityTins()).isEmpty();
        assertThat(parse("{\"ret_cd\":\"0\",\"legal_info\":\"n/a\"}").legalEntityTins()).isEmpty();
    }

    private static OneIdUserInfo parse(String json) throws Exception {
        JsonNode node = JSON.readTree(json);
        return OneIdUserInfo.from(node);
    }
}
