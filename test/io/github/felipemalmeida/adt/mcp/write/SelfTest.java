package io.github.felipemalmeida.adt.mcp.write;

import java.util.List;
import java.util.Map;

/**
 * Autoteste das partes que nao falam com o SAP: parser de argumentos, travas e
 * leitura das respostas XML. Roda fora do Eclipse:
 *
 *   .\selftest.ps1
 */
public final class SelfTest {

   private static int failures;

   public static void main(String[] args) {
      jsonParsing();
      jsonEscaping();
      objectNames();
      uriNormalization();
      customObjectGuard();
      destinationGuard();
      xmlReading();

      if (failures > 0) {
         System.out.println(failures + " teste(s) falharam");
         System.exit(1);
      }
      System.out.println("Todos os testes passaram");
   }

   private static void jsonParsing() {
      Map<String, Object> arguments = Json.parseObject(
            "{\"objectUri\":\"/sap/bc/adt/oo/classes/zcl_x\","
            + "\"source\":\"CLASS zcl_x DEFINITION.\\n  \\\"comentario com aspas\\\"\\nENDCLASS.\","
            + "\"activate\":false,\"tab\":\"a\\tb\",\"unicode\":\"\\u00e7\"}");

      is("chave simples", "/sap/bc/adt/oo/classes/zcl_x", Json.str(arguments, "objectUri", null));
      is("quebra de linha preservada", true, Json.str(arguments, "source", "").contains("\n  \"comentario"));
      is("boolean lido", false, Json.bool(arguments, "activate", true));
      is("default quando ausente", true, Json.bool(arguments, "naoExiste", true));
      is("tab", "a\tb", Json.str(arguments, "tab", null));
      is("escape unicode", "\u00e7", Json.str(arguments, "unicode", null));
      is("entrada vazia vira mapa vazio", true, Json.parseObject("").isEmpty());
   }

   private static void jsonEscaping() {
      is("aspas escapadas", "\"a\\\"b\"", Json.escape("a\"b"));
      is("barra invertida escapada", "\"a\\\\b\"", Json.escape("a\\b"));
      is("quebra de linha escapada", "\"a\\nb\"", Json.escape("a\nb"));
      is("nulo", "null", Json.escape(null));
      // O que sai do escape tem de voltar pelo parser: o fonte ABAP passa
      // pelos dois lados.
      String source = "REPORT z.\n WRITE: / 'x\\y', \"aspas\".";
      Map<String, Object> roundTrip = Json.parseObject("{\"s\":" + Json.escape(source) + "}");
      is("ida e volta", source, Json.str(roundTrip, "s", null));
   }

   private static void objectNames() {
      is("classe", "zcl_x", Guards.objectName("/sap/bc/adt/oo/classes/zcl_x"));
      is("com query", "zcl_x", Guards.objectName("/sap/bc/adt/oo/classes/zcl_x?_action=LOCK"));
      is("com barra final", "zcl_x", Guards.objectName("/sap/bc/adt/oo/classes/zcl_x/"));
      is("namespace codificado", "/abc/cl_x", Guards.objectName("/sap/bc/adt/oo/classes/%2fabc%2fcl_x"));
   }

   private static void uriNormalization() {
      is("barra inicial adicionada", "/sap/bc/adt/oo/classes/zcl_x",
            Guards.normalizeObjectUri("sap/bc/adt/oo/classes/zcl_x"));
      is("barra final removida", "/sap/bc/adt/oo/classes/zcl_x",
            Guards.normalizeObjectUri("/sap/bc/adt/oo/classes/zcl_x/"));
      rejects("query string", () -> Guards.normalizeObjectUri("/sap/bc/adt/oo/classes/zcl_x?_action=LOCK"));
      rejects("fora do ADT", () -> Guards.normalizeObjectUri("/sap/opu/odata/x"));
      rejects("vazio", () -> Guards.normalizeObjectUri(null));
   }

   private static void customObjectGuard() {
      Guards.assertCustomObject("/sap/bc/adt/oo/classes/zcl_x");
      Guards.assertCustomObject("/sap/bc/adt/oo/classes/ZCL_X");
      Guards.assertCustomObject("/sap/bc/adt/programs/includes/yexemplo_i");
      Guards.assertCustomObject("/sap/bc/adt/oo/classes/%2fabc%2fcl_x");
      rejects("classe standard", () -> Guards.assertCustomObject("/sap/bc/adt/oo/classes/cl_gui_alv_grid"));
      rejects("programa standard", () -> Guards.assertCustomObject("/sap/bc/adt/programs/programs/rsusr002"));
   }

   private static void destinationGuard() {
      Guards.assertWritableDestination("DEV_100_dev_en");
      rejects("destino produtivo", () -> Guards.assertWritableDestination("PRD_100_user_pt"));
      rejects("destino produtivo minusculo", () -> Guards.assertWritableDestination("s4prod_200"));

      System.setProperty("adt.mcp.write.blockedDestinations", "(?i)nunca.*");
      try {
         Guards.assertWritableDestination("PRD_100_user_pt");
         rejects("bloqueio configurado", () -> Guards.assertWritableDestination("NUNCA_ESCREVA"));
      } finally {
         System.clearProperty("adt.mcp.write.blockedDestinations");
      }

      System.setProperty("adt.mcp.destination", "DESTINO_DA_PROPERTY");
      try {
         is("argumento vence", "DO_ARGUMENTO", Guards.resolveDestination("DO_ARGUMENTO"));
         is("cai na property", "DESTINO_DA_PROPERTY", Guards.resolveDestination(null));
      } finally {
         System.clearProperty("adt.mcp.destination");
      }
   }

   private static void xmlReading() {
      String lockResponse = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<asx:abap xmlns:asx=\"http://www.sap.com/abapxml\" version=\"1.0\"><asx:values><DATA>"
            + "<LOCK_HANDLE>Ac0mLW6vP1xUY2h0</LOCK_HANDLE><CORRNR>DE1K900123</CORRNR><CORRUSER>FELIPE</CORRUSER>"
            + "</DATA></asx:values></asx:abap>";
      is("lock handle", "Ac0mLW6vP1xUY2h0", AdtXml.firstText(lockResponse, "LOCK_HANDLE"));
      is("transporte sugerido", "DE1K900123", AdtXml.firstText(lockResponse, "CORRNR"));
      is("tag ausente", null, AdtXml.firstText(lockResponse, "NAO_EXISTE"));

      String activationResponse = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\">"
            + "<msg objDescr=\"Class ZCL_X\" type=\"E\" line=\"12\" href=\"/sap/bc/adt/oo/classes/zcl_x\">"
            + "<shortText><txt>Field LV_A is unknown</txt></shortText></msg>"
            + "<msg objDescr=\"Class ZCL_X\" type=\"W\" line=\"3\" href=\"\">"
            + "<shortText><txt>Variavel nao usada</txt></shortText></msg>"
            + "</chkl:messages>";
      List<AdtXml.Message> messages = AdtXml.messages(activationResponse);
      is("duas mensagens", 2, messages.size());
      is("texto da primeira", "Field LV_A is unknown", messages.get(0).text);
      is("primeira e erro", true, messages.get(0).isError());
      is("segunda e warning", false, messages.get(1).isError());
      is("erro detectado na lista", true, Results.hasError(messages));

      String emptyActivation = "";
      is("ativacao sem corpo nao tem mensagem", 0, AdtXml.messages(emptyActivation).size());
      is("sem mensagem nao e erro", false, Results.hasError(AdtXml.messages(emptyActivation)));

      String exception = "<exc:exception xmlns:exc=\"http://www.sap.com/adt/exception\">"
            + "<localizedMessage>Objeto esta bloqueado por outro usuario</localizedMessage></exc:exception>";
      is("mensagem de erro", "Objeto esta bloqueado por outro usuario", AdtXml.errorText(exception));
      is("corpo nao-XML vira texto", "erro cru", AdtXml.errorText("erro cru"));
   }

   private static void is(String what, Object expected, Object actual) {
      boolean equal = expected == null ? actual == null : expected.equals(actual);
      if (!equal) {
         failures++;
         System.out.println("FALHOU  " + what + ": esperado <" + expected + ">, veio <" + actual + ">");
      } else {
         System.out.println("ok      " + what);
      }
   }

   private static void rejects(String what, Runnable action) {
      try {
         action.run();
         failures++;
         System.out.println("FALHOU  " + what + ": deveria ter sido recusado");
      } catch (RuntimeException e) {
         System.out.println("ok      " + what + " recusado: " + e.getMessage());
      }
   }
}
