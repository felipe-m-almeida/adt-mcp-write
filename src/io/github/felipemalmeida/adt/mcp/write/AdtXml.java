package io.github.felipemalmeida.adt.mcp.write;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/** Leitura das poucas respostas XML do ADT que interessam a este bundle. */
final class AdtXml {

   /**
    * O parser padrao despeja erro no stderr do Eclipse antes de lancar. Aqui
    * corpo invalido e caso esperado (resposta de erro em HTML, corpo vazio),
    * tratado por quem chama.
    */
   private static final ErrorHandler SILENT = new ErrorHandler() {

      @Override
      public void warning(SAXParseException e) {
      }

      @Override
      public void error(SAXParseException e) {
      }

      @Override
      public void fatalError(SAXParseException e) throws SAXParseException {
         throw e;
      }
   };

   private AdtXml() {
   }

   /**
    * Texto do primeiro elemento com esse nome local. O ADT mistura prefixos
    * (asx:, adtcore:, chkl:) conforme o endpoint, entao a busca ignora prefixo.
    */
   static String firstText(String xml, String localName) {
      for (Element element : elements(xml)) {
         if (localNameOf(element).equals(localName)) {
            return element.getTextContent().trim();
         }
      }
      return null;
   }

   /** Mensagens de uma resposta de ativacao (elementos {@code msg}). */
   static List<Message> messages(String xml) {
      List<Message> messages = new ArrayList<>();
      for (Element element : elements(xml)) {
         if (!localNameOf(element).equals("msg")) {
            continue;
         }
         String type = element.getAttribute("type");
         String text = textOfChild(element, "txt");
         if (text == null) {
            text = element.getTextContent().trim();
         }
         messages.add(new Message(type, element.getAttribute("objDescr"), text, element.getAttribute("href")));
      }
      return messages;
   }

   /**
    * Mensagem legivel de uma resposta de erro do ADT, que vem ora como
    * exception ADT, ora como texto solto.
    */
   static String errorText(String xml) {
      String message = firstText(xml, "localizedMessage");
      if (message == null) {
         message = firstText(xml, "message");
      }
      if (message == null) {
         message = firstText(xml, "txt");
      }
      if (message == null || message.isEmpty()) {
         String raw = xml == null ? "" : xml.trim();
         return raw.length() > 500 ? raw.substring(0, 500) + "..." : raw;
      }
      return message;
   }

   private static String textOfChild(Element parent, String localName) {
      NodeList children = parent.getElementsByTagName("*");
      for (int i = 0; i < children.getLength(); i++) {
         Node node = children.item(i);
         if (node instanceof Element && localNameOf((Element) node).equals(localName)) {
            return node.getTextContent().trim();
         }
      }
      return null;
   }

   private static List<Element> elements(String xml) {
      List<Element> elements = new ArrayList<>();
      if (xml == null || xml.trim().isEmpty()) {
         return elements;
      }
      try {
         DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
         factory.setNamespaceAware(false);
         factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
         DocumentBuilder builder = factory.newDocumentBuilder();
         builder.setErrorHandler(SILENT);
         Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
         NodeList all = document.getElementsByTagName("*");
         for (int i = 0; i < all.getLength(); i++) {
            elements.add((Element) all.item(i));
         }
      } catch (Exception e) {
         // Resposta que nao e XML (HTML de erro, corpo vazio): quem chamou cai
         // no tratamento por status code.
      }
      return elements;
   }

   private static String localNameOf(Element element) {
      String name = element.getTagName();
      int colon = name.indexOf(':');
      return colon >= 0 ? name.substring(colon + 1) : name;
   }

   static final class Message {

      final String type;
      final String objectDescription;
      final String text;
      final String href;

      Message(String type, String objectDescription, String text, String href) {
         this.type = type == null ? "" : type;
         this.objectDescription = objectDescription == null ? "" : objectDescription;
         this.text = text == null ? "" : text;
         this.href = href == null ? "" : href;
      }

      boolean isError() {
         return type.equalsIgnoreCase("E") || type.equalsIgnoreCase("A") || type.equalsIgnoreCase("X");
      }

      String toJson() {
         return "{\"type\":" + Json.escape(type) + ",\"object\":" + Json.escape(objectDescription) + ",\"text\":"
               + Json.escape(text) + ",\"href\":" + Json.escape(href) + "}";
      }
   }
}
