package io.github.felipemalmeida.adt.mcp.write;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * As quatro chamadas ADT que compoem uma edicao de fonte: LOCK, PUT do source,
 * ativacao e UNLOCK. Sao mantidas juntas porque so fazem sentido em sequencia
 * e na mesma sessao.
 */
final class AdtEditing {

   private static final String ACCEPT_LOCK =
         "application/vnd.sap.as+xml;charset=UTF-8;dataname=com.sap.adt.lock.Result";
   private static final String ACTIVATION_URI = "/sap/bc/adt/activation?method=activate&preauditRequests=false";
   private static final Pattern CLASS_URI = Pattern.compile("(?i)^/sap/bc/adt/oo/classes/[^/?]+$");

   private AdtEditing() {
   }

   static Lock lock(AdtSession session, String objectUri) throws IOException {
      AdtSession.Response response = session.post(objectUri + "?_action=LOCK&accessMode=MODIFY", ACCEPT_LOCK, null,
            new byte[0]);
      if (!response.ok()) {
         throw new IOException("LOCK falhou (HTTP " + response.status + "): " + AdtXml.errorText(response.body));
      }
      String handle = AdtXml.firstText(response.body, "LOCK_HANDLE");
      if (handle == null || handle.isEmpty()) {
         throw new IOException("LOCK devolveu HTTP " + response.status + " sem LOCK_HANDLE: " + response.body);
      }
      return new Lock(handle, AdtXml.firstText(response.body, "CORRNR"));
   }

   static void unlock(AdtSession session, String objectUri, String lockHandle) throws IOException {
      AdtSession.Response response = session.post(
            objectUri + "?_action=UNLOCK&lockHandle=" + encode(lockHandle), "*/*", null, new byte[0]);
      if (!response.ok()) {
         throw new IOException("UNLOCK falhou (HTTP " + response.status + "): " + AdtXml.errorText(response.body));
      }
   }

   static void putSource(AdtSession session, String sourceUri, String lockHandle, String transport, String source)
         throws IOException {
      StringBuilder uri = new StringBuilder(sourceUri).append("?lockHandle=").append(encode(lockHandle));
      if (transport != null && !transport.isEmpty()) {
         uri.append("&corrNr=").append(encode(transport));
      }
      AdtSession.Response response = session.put(uri.toString(), "text/plain", "text/plain; charset=utf-8",
            source.getBytes(StandardCharsets.UTF_8));
      if (!response.ok()) {
         throw new IOException("PUT do fonte falhou (HTTP " + response.status + "): "
               + AdtXml.errorText(response.body));
      }
   }

   /**
    * O sourceUri e o include de testes (CCAU) da propria classe do objectUri?
    * Aceita com e sem o /source/main no fim.
    */
   static boolean isClassTestInclude(String objectUri, String sourceUri) {
      if (!CLASS_URI.matcher(objectUri).matches()) {
         return false;
      }
      String include = sourceUri.replaceAll("(?i)/source/main$", "");
      return include.equalsIgnoreCase(objectUri + "/includes/testclasses");
   }

   /**
    * Cria o include de testes da classe quando ele ainda nao existe. No Eclipse
    * isso e a aba "Test Classes"; pelo ADT e um POST em /includes que exige o
    * lock da classe na mesma sessao — por isso so da para fazer aqui, entre o
    * LOCK e o PUT. Devolve true quando criou. GET 404 e o sinal de "nao existe";
    * qualquer outro erro no GET e reportado, para nao criar por engano.
    */
   static boolean createClassTestIncludeIfMissing(AdtSession session, String objectUri, String sourceUri,
         String lockHandle, String transport) throws IOException {
      if (!isClassTestInclude(objectUri, sourceUri)) {
         return false;
      }
      AdtSession.Response existing = session.get(objectUri + "/includes/testclasses", "text/plain");
      if (existing.ok()) {
         return false;
      }
      if (existing.status != 404) {
         throw new IOException("Leitura do include de testes falhou (HTTP " + existing.status + "): "
               + AdtXml.errorText(existing.body));
      }
      StringBuilder uri = new StringBuilder(objectUri).append("/includes?lockHandle=").append(encode(lockHandle));
      if (transport != null && !transport.isEmpty()) {
         uri.append("&corrNr=").append(encode(transport));
      }
      String payload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<class:abapClassInclude xmlns:class=\"http://www.sap.com/adt/oo/classes\""
            + " xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:name=\"dummy\""
            + " class:includeType=\"testclasses\"/>";
      AdtSession.Response response = session.post(uri.toString(), "application/xml",
            "application/vnd.sap.adt.oo.classincludes+xml", payload.getBytes(StandardCharsets.UTF_8));
      if (!response.ok()) {
         throw new IOException("Criacao do include de testes falhou (HTTP " + response.status + "): "
               + AdtXml.errorText(response.body));
      }
      return true;
   }

   /**
    * Ativa o objeto e devolve as mensagens do checklist. HTTP 200 aqui nao
    * significa objeto ativo: erro de sintaxe volta como mensagem tipo E dentro
    * de uma resposta bem-sucedida.
    */
   static List<AdtXml.Message> activate(AdtSession session, String objectUri, String objectName) throws IOException {
      String payload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">"
            + "<adtcore:objectReference adtcore:uri=\"" + xmlAttribute(objectUri) + "\" adtcore:name=\""
            + xmlAttribute(objectName.toUpperCase()) + "\"/>"
            + "</adtcore:objectReferences>";
      AdtSession.Response response = session.post(ACTIVATION_URI, "application/xml", "application/xml",
            payload.getBytes(StandardCharsets.UTF_8));
      if (!response.ok()) {
         throw new IOException("Ativacao falhou (HTTP " + response.status + "): " + AdtXml.errorText(response.body));
      }
      return AdtXml.messages(response.body);
   }

   private static String encode(String value) {
      try {
         return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
         throw new IllegalStateException(e);
      }
   }

   private static String xmlAttribute(String value) {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
   }

   static final class Lock {

      final String handle;
      /** Transporte que o proprio SAP sugeriu no lock, quando ha um. */
      final String suggestedTransport;

      Lock(String handle, String suggestedTransport) {
         this.handle = handle;
         this.suggestedTransport = suggestedTransport == null ? "" : suggestedTransport.trim();
      }
   }
}
