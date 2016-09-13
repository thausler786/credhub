package io.pivotal.security.mapper;

import com.greghaskins.spectrum.Spectrum;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import io.pivotal.security.CredentialManagerApp;
import io.pivotal.security.view.ParameterizedValidationException;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static com.google.common.collect.ImmutableSet.of;
import static com.greghaskins.spectrum.Spectrum.describe;
import static com.greghaskins.spectrum.Spectrum.it;
import static com.jayway.jsonpath.JsonPath.using;
import static io.pivotal.security.helper.SpectrumHelper.wireAndUnwire;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.collection.IsArrayContainingInOrder.arrayContaining;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(Spectrum.class)
@SpringApplicationConfiguration(classes = CredentialManagerApp.class)
@ActiveProfiles("unit-test")
public class RequestTranslatorTest {
  @Autowired
  Configuration jsonPathConfiguration;

  {
    RequestTranslator subject = new RequestTranslator() {

      @Override
      public void populateEntityFromJson(Object namedSecret, DocumentContext documentContext) {
      }

      @Override
      public Set<String> getValidKeys() {
        return of("$['foo']", "$['bar']", "$['baz']", "$['baz']['quux']");
      }
    };

    wireAndUnwire(this);

    describe("populating entity from JSON", () -> {
      it("can accept all these valid keys", () -> {
        String requestBody = "{\"foo\":\"value\",\"bar\":\"\",\"baz\":{\"quux\":false}}";
        subject.validateJsonKeys(using(jsonPathConfiguration).parse(requestBody));
      });

      it("rejects additional keys at top level", () -> {
        String requestJson = "{\"foo1\":\"value\",\"bar\":\"\",\"baz\":{\"quux\":false}}";
        doInvalidTest(subject, requestJson, "$['foo1']");
      });

      it("rejects additional keys at a lower level", () -> {
        String requestJson = "{\"foo\":\"value\",\"bar\":\"\",\"baz\":{\"quux1\":false}}";
        doInvalidTest(subject, requestJson, "$['baz']['quux1']");
      });
    });
  }

  private void doInvalidTest(RequestTranslator subject, String requestBody, String invalidKey) {
    try {
      subject.validateJsonKeys(using(jsonPathConfiguration).parse(requestBody));
      fail();
    } catch (ParameterizedValidationException e) {
      assertThat(e.getMessage(), equalTo("error.invalid_json_key"));
      assertThat(e.getParameters(), arrayContaining(invalidKey));
    }
  }
}