package io.github.felipemalmeida.adt.mcp.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Grava o fonte de um objeto ABAP existente e, por padrao, ativa.
 *
 * O ciclo LOCK/PUT/ativacao/UNLOCK e uma operacao so, sem lock e unlock
 * expostos como ferramentas separadas: um lock orfao deixa o objeto travado
 * para o usuario no Eclipse, e um agente que perde o fio no meio da sequencia
 * nao teria como desfazer.
 */
public class WriteSourceTool implements IAdtMCPTool {

   @Override
   public String getName() {
      return "adt_write_source";
   }

   @Override
   public String getDescription() {
      return "Substitui o fonte completo de um objeto ABAP existente (classe, programa, include, function group, "
            + "DDL source) em um destino ADT e ativa em seguida. Executa LOCK, PUT do fonte, ativacao e UNLOCK numa "
            + "unica sessao stateful, sempre desbloqueando o objeto no fim. Recusa objeto fora do namespace de "
            + "cliente (Z*, Y*, /NAMESPACE/) e destino marcado como produtivo. O conteudo enviado substitui o fonte "
            + "inteiro: leia o fonte atual antes e mande o texto completo, nunca um trecho. HTTP 200 na ativacao nao "
            + "garante objeto ativo - confira o campo activated e as mensagens devolvidas.";
   }

   @Override
   public String getInputSchema() {
      return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"objectUri\":{\"type\":\"string\",\"description\":\"URI ADT do objeto, sem query string. "
            + "Ex: /sap/bc/adt/oo/classes/zcl_exemplo ou /sap/bc/adt/programs/includes/zexemplo_include\"},"
            + "\"source\":{\"type\":\"string\",\"description\":\"Fonte completo que substitui o atual\"},"
            + "\"sourceUri\":{\"type\":\"string\",\"description\":\"URI do fonte quando nao for o padrao "
            + "objectUri + /source/main. Ex: .../includes/implementations\"},"
            + "\"transport\":{\"type\":\"string\",\"description\":\"Ordem de transporte. Se omitido usa a que o "
            + "proprio SAP sugerir no LOCK\"},"
            + "\"activate\":{\"type\":\"boolean\",\"description\":\"Ativar depois de gravar. Padrao true\"},"
            + "\"destination\":{\"type\":\"string\",\"description\":\"Destino ADT. Se omitido usa o configurado no "
            + "eclipse.ini\"}"
            + "},"
            + "\"required\":[\"objectUri\",\"source\"]"
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
         String source = Json.str(arguments, "source", null);
         if (source == null) {
            throw new IllegalArgumentException("source e obrigatorio");
         }
         String sourceUri = Json.str(arguments, "sourceUri", objectUri + "/source/main");
         boolean activate = Json.bool(arguments, "activate", true);
         String destination = Guards.resolveDestination(Json.str(arguments, "destination", null));

         Guards.assertWritableDestination(destination);
         Guards.assertCustomObject(objectUri);

         return write(destination, objectUri, sourceUri, source, Json.str(arguments, "transport", null), activate,
               monitor);
      } catch (Exception e) {
         return Results.error(e);
      }
   }

   private IAdtMcpToolCallResult write(String destination, String objectUri, String sourceUri, String source,
         String requestedTransport, boolean activate, IProgressMonitor monitor) throws Exception {
      try (AdtSession session = new AdtSession(destination, monitor)) {
         AdtEditing.Lock lock = AdtEditing.lock(session, objectUri);
         String transport = requestedTransport != null ? requestedTransport : lock.suggestedTransport;
         try {
            AdtEditing.putSource(session, sourceUri, lock.handle, transport, source);
         } finally {
            AdtEditing.unlock(session, objectUri, lock.handle);
         }

         // A ativacao vem depois do UNLOCK: com o objeto ainda travado, o
         // servidor recusa com 403 "usuario ja esta processando <objeto>" —
         // o proprio lock desta sessao e quem barra.
         List<AdtXml.Message> messages = new ArrayList<>();
         boolean activated = false;
         if (activate) {
            messages = AdtEditing.activate(session, objectUri, Guards.objectName(objectUri));
            activated = !Results.hasError(messages);
         }

         StringBuilder json = new StringBuilder(512);
         json.append("{\"written\":true")
               .append(",\"activated\":").append(activated)
               .append(",\"destination\":").append(Json.escape(destination))
               .append(",\"objectUri\":").append(Json.escape(objectUri))
               .append(",\"sourceUri\":").append(Json.escape(sourceUri))
               .append(",\"transport\":").append(Json.escape(transport))
               .append(",\"messages\":").append(Results.messagesToJson(messages))
               .append("}");

         boolean failed = activate && !activated;
         return AdtMcpToolCallResultBuilder.builder()
               .withContent(failed ? "Fonte gravado, mas a ativacao falhou. " + json : json.toString())
               .isError(failed)
               .build();
      }
   }
}
