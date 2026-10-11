package io.github.sunjirapath.slipverify.examples.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sunjirapath.promptparse.SlipVerify;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTest {
  @Autowired MockMvc mvc;

  @Test
  void payloadWithoutProviderIsUnverified() throws Exception {
    String payload = SlipVerify.build("004", "DEMO-1");
    mvc.perform(
            post("/slips/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":\"" + payload + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNVERIFIED"))
        .andExpect(jsonPath("$.transRef").value("DEMO-1"));
  }

  @Test
  void garbageGetsAGenericError() throws Exception {
    mvc.perform(post("/slips/verify").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
  }
}
