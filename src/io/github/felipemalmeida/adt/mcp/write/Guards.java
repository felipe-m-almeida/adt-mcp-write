package io.github.felipemalmeida.adt.mcp.write;

import java.util.regex.Pattern;

/**
 * Travas que valem para toda escrita feita por este bundle. Sao deliberadamente
 * do lado do Eclipse e nao do lado do agente: uma regra que so existe no prompt
 * some junto com o contexto.
 */
final class Guards {

   /** Objeto do cliente: Z*, Y* ou namespace registrado (/ABC/...). */
   private static final Pattern CUSTOM_OBJECT = Pattern.compile("^(?:[zyZY].*|/[A-Za-z0-9_]+/.*)$");

   private static final String DEFAULT_BLOCKED_DESTINATIONS = "(?i).*(prd|prod).*";

   private Guards() {
   }

   /**
    * Resolve o destino ADT: o do argumento vence; senao cai no configurado por
    * -D no eclipse.ini, inclusive o do ARC-1, para nao ter de repetir o destino
    * em toda chamada.
    */
   static String resolveDestination(String fromArguments) {
      if (fromArguments != null && !fromArguments.isEmpty()) {
         return fromArguments;
      }
      String configured = System.getProperty("adt.mcp.destination");
      if (configured == null || configured.isEmpty()) {
         configured = System.getProperty("arc1.mcp.destination");
      }
      if (configured == null || configured.isEmpty()) {
         throw new IllegalArgumentException(
               "Destino ADT nao informado: passe \"destination\" ou configure -Dadt.mcp.destination no eclipse.ini");
      }
      return configured;
   }

   /**
    * Recusa destino que parece produtivo. O padrao pega nomes com PRD/PROD e
    * pode ser trocado por -Dadt.mcp.write.blockedDestinations=<regex>.
    */
   static void assertWritableDestination(String destination) {
      String pattern = System.getProperty("adt.mcp.write.blockedDestinations", DEFAULT_BLOCKED_DESTINATIONS);
      if (pattern.isEmpty()) {
         return;
      }
      if (Pattern.compile(pattern).matcher(destination).matches()) {
         throw new IllegalStateException("Destino " + destination
               + " esta bloqueado para escrita por adt.mcp.write.blockedDestinations");
      }
   }

   /**
    * Recusa objeto standard da SAP. A checagem e pelo nome porque o namespace
    * do objeto e o unico dado confiavel antes do lock — e o lock ja seria uma
    * escrita no sistema.
    */
   static void assertCustomObject(String objectUri) {
      String name = objectName(objectUri);
      if (!CUSTOM_OBJECT.matcher(name).matches()) {
         throw new IllegalStateException("Objeto " + name
               + " nao esta em namespace de cliente (Z*, Y* ou /NAMESPACE/): este bundle nao altera objeto standard");
      }
   }

   /** Ultimo segmento do URI, sem query string, com %2f devolvido a barra. */
   static String objectName(String objectUri) {
      String path = objectUri;
      int query = path.indexOf('?');
      if (query >= 0) {
         path = path.substring(0, query);
      }
      while (path.endsWith("/")) {
         path = path.substring(0, path.length() - 1);
      }
      int slash = path.lastIndexOf('/');
      String name = slash >= 0 ? path.substring(slash + 1) : path;
      return name.replaceAll("(?i)%2f", "/");
   }

   /** Normaliza o URI recebido do agente para o caminho absoluto do ADT. */
   static String normalizeObjectUri(String objectUri) {
      if (objectUri == null || objectUri.trim().isEmpty()) {
         throw new IllegalArgumentException("objectUri e obrigatorio");
      }
      String uri = objectUri.trim();
      if (uri.indexOf('?') >= 0) {
         throw new IllegalArgumentException("objectUri nao aceita query string: " + uri);
      }
      if (!uri.startsWith("/")) {
         uri = "/" + uri;
      }
      if (!uri.startsWith("/sap/bc/adt/")) {
         throw new IllegalArgumentException("objectUri deve comecar com /sap/bc/adt/ : " + uri);
      }
      while (uri.endsWith("/")) {
         uri = uri.substring(0, uri.length() - 1);
      }
      return uri;
   }
}
