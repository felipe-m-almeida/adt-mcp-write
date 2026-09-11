package io.github.felipemalmeida.adt.mcp.write;

import java.util.List;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/** Formato de resposta comum as ferramentas deste bundle. */
final class Results {

   private Results() {
   }

   static IAdtMcpToolCallResult error(Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return AdtMcpToolCallResultBuilder.builder()
            .withContent("{\"error\":" + Json.escape(message) + "}")
            .isError(true)
            .build();
   }

   static boolean hasError(List<AdtXml.Message> messages) {
      for (AdtXml.Message message : messages) {
         if (message.isError()) {
            return true;
         }
      }
      return false;
   }

   static String messagesToJson(List<AdtXml.Message> messages) {
      StringBuilder json = new StringBuilder("[");
      for (int i = 0; i < messages.size(); i++) {
         if (i > 0) {
            json.append(',');
         }
         json.append(messages.get(i).toJson());
      }
      return json.append(']').toString();
   }
}
