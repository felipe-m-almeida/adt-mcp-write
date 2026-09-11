package io.github.felipemalmeida.adt.mcp.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser/serializador JSON minimalista. Existe para o bundle nao depender de
 * nenhuma biblioteca fora da plataforma Eclipse — qualquer jar extra teria de
 * ser embutido no dropins junto, o que complicaria a instalacao.
 */
final class Json {

   private final String text;
   private int pos;

   private Json(String text) {
      this.text = text;
   }

   @SuppressWarnings("unchecked")
   static Map<String, Object> parseObject(String text) {
      if (text == null || text.trim().isEmpty()) {
         return new LinkedHashMap<>();
      }
      Object value = new Json(text).parseValue();
      if (!(value instanceof Map)) {
         throw new IllegalArgumentException("Esperado um objeto JSON na entrada");
      }
      return (Map<String, Object>) value;
   }

   static String str(Map<String, Object> arguments, String key, String fallback) {
      Object value = arguments.get(key);
      if (value == null) {
         return fallback;
      }
      String text = String.valueOf(value).trim();
      return text.isEmpty() ? fallback : text;
   }

   static boolean bool(Map<String, Object> arguments, String key, boolean fallback) {
      Object value = arguments.get(key);
      if (value instanceof Boolean) {
         return ((Boolean) value).booleanValue();
      }
      if (value instanceof String) {
         return Boolean.parseBoolean(((String) value).trim());
      }
      return fallback;
   }

   static String escape(String raw) {
      if (raw == null) {
         return "null";
      }
      StringBuilder out = new StringBuilder(raw.length() + 16).append('"');
      for (int i = 0; i < raw.length(); i++) {
         char c = raw.charAt(i);
         switch (c) {
         case '"':
            out.append("\\\"");
            break;
         case '\\':
            out.append("\\\\");
            break;
         case '\n':
            out.append("\\n");
            break;
         case '\r':
            out.append("\\r");
            break;
         case '\t':
            out.append("\\t");
            break;
         default:
            if (c < 0x20) {
               out.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
               out.append(c);
            }
         }
      }
      return out.append('"').toString();
   }

   private Object parseValue() {
      skipWhitespace();
      char c = peek();
      switch (c) {
      case '{':
         return parseMap();
      case '[':
         return parseList();
      case '"':
         return parseString();
      case 't':
         expect("true");
         return Boolean.TRUE;
      case 'f':
         expect("false");
         return Boolean.FALSE;
      case 'n':
         expect("null");
         return null;
      default:
         return parseNumber();
      }
   }

   private Map<String, Object> parseMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (peek() == '}') {
         pos++;
         return map;
      }
      while (true) {
         skipWhitespace();
         String key = parseString();
         skipWhitespace();
         if (peek() != ':') {
            throw error("esperado ':'");
         }
         pos++;
         map.put(key, parseValue());
         skipWhitespace();
         char c = peek();
         pos++;
         if (c == '}') {
            return map;
         }
         if (c != ',') {
            throw error("esperado ',' ou '}'");
         }
      }
   }

   private List<Object> parseList() {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (peek() == ']') {
         pos++;
         return list;
      }
      while (true) {
         list.add(parseValue());
         skipWhitespace();
         char c = peek();
         pos++;
         if (c == ']') {
            return list;
         }
         if (c != ',') {
            throw error("esperado ',' ou ']'");
         }
      }
   }

   private String parseString() {
      if (peek() != '"') {
         throw error("esperado aspas");
      }
      pos++;
      StringBuilder out = new StringBuilder();
      while (true) {
         char c = next();
         if (c == '"') {
            return out.toString();
         }
         if (c != '\\') {
            out.append(c);
            continue;
         }
         char escaped = next();
         switch (escaped) {
         case '"':
            out.append('"');
            break;
         case '\\':
            out.append('\\');
            break;
         case '/':
            out.append('/');
            break;
         case 'b':
            out.append('\b');
            break;
         case 'f':
            out.append('\f');
            break;
         case 'n':
            out.append('\n');
            break;
         case 'r':
            out.append('\r');
            break;
         case 't':
            out.append('\t');
            break;
         case 'u':
            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
            break;
         default:
            throw error("escape invalido: " + escaped);
         }
      }
   }

   private Object parseNumber() {
      int start = pos;
      while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
         pos++;
      }
      String raw = text.substring(start, pos);
      if (raw.isEmpty()) {
         throw error("valor JSON invalido");
      }
      return Double.valueOf(raw);
   }

   private void expect(String literal) {
      if (!text.startsWith(literal, pos)) {
         throw error("esperado " + literal);
      }
      pos += literal.length();
   }

   private void skipWhitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
         pos++;
      }
   }

   private char peek() {
      if (pos >= text.length()) {
         throw error("fim inesperado da entrada");
      }
      return text.charAt(pos);
   }

   private char next() {
      char c = peek();
      pos++;
      return c;
   }

   private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("JSON invalido na posicao " + pos + ": " + message);
   }
}
