package io.github.felipemalmeida.adt.mcp.write;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Ativa um objeto que ficou inativo. Existe separada da gravacao para o caso em
 * que o fonte ja foi gravado e so a ativacao falhou — reenviar o fonte inteiro
 * so para ativar seria pior.
 */
public class ActivateTool implements IAdtMCPTool {

   @Override
   public String getName() {
      return "adt_activate";
   }

   @Override
   public String getDescription() {
      return "Ativa um objeto ABAP ja gravado em um destino ADT, sem alterar o fonte. Devolve as mensagens do "
            + "checklist de ativacao: HTTP 200 nao garante objeto ativo, confira o campo activated. Recusa objeto "
            + "fora do namespace de cliente (Z*, Y*, /NAMESPACE/) e destino marcado como produtivo.";
   }

   @Override
   public String getInputSchema() {
      return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"objectUri\":{\"type\":\"string\",\"description\":\"URI ADT do objeto, sem query string. "
            + "Ex: /sap/bc/adt/oo/classes/zcl_exemplo\"},"
            + "\"destination\":{\"type\":\"string\",\"description\":\"Destino ADT. Se omitido usa o configurado no "
            + "eclipse.ini\"}"
            + "},"
            + "\"required\":[\"objectUri\"]"
            + "}";
   }

   @Override
   public IAdtMcpToolCallResult execute(String input) {
      return execute(input, new NullProgressMonitor());
   }

   @Override
   public IAdtMcpToolCallResult execute(String input, IProgressMonitor monitor) {
      try {
         Map<String, Object> arguments = Json.parseObject(input);
         String objectUri = Guards.normalizeObjectUri(Json.str(arguments, "objectUri", null));
         String destination = Guards.resolveDestination(Json.str(arguments, "destination", null));

         Guards.assertWritableDestination(destination);
         Guards.assertCustomObject(objectUri);

         try (AdtSession session = new AdtSession(destination, monitor)) {
            List<AdtXml.Message> messages = AdtEditing.activate(session, objectUri, Guards.objectName(objectUri));
            boolean activated = !Results.hasError(messages);
            String json = "{\"activated\":" + activated
                  + ",\"destination\":" + Json.escape(destination)
                  + ",\"objectUri\":" + Json.escape(objectUri)
                  + ",\"messages\":" + Results.messagesToJson(messages)
                  + "}";
            return AdtMcpToolCallResultBuilder.builder().withContent(json).isError(!activated).build();
         }
      } catch (Exception e) {
         return Results.error(e);
      }
   }
}
