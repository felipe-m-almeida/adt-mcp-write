package io.github.felipemalmeida.adt.mcp.write;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.IProgressMonitor;

import com.sap.adt.communication.message.AdtRequestFactory;
import com.sap.adt.communication.message.ByteArrayMessageBody;
import com.sap.adt.communication.message.HeadersFactory;
import com.sap.adt.communication.message.IHeaders;
import com.sap.adt.communication.message.IMessageBody;
import com.sap.adt.communication.message.IRequest;
import com.sap.adt.communication.message.IResponse;
import com.sap.adt.communication.session.AdtSystemSessionFactory;
import com.sap.adt.communication.session.IStatefulSystemSession;

/**
 * Sessao stateful contra um destino ADT do workspace. Stateful e obrigatorio:
 * o lock handle devolvido pelo _action=LOCK so vale dentro da mesma sessao que
 * executa o PUT do fonte.
 */
final class AdtSession implements AutoCloseable {

   private static final int MAX_BODY_BYTES = 1 << 20;

   private final IStatefulSystemSession session;
   private final IProgressMonitor monitor;

   AdtSession(String destination, IProgressMonitor monitor) throws Exception {
      this.session = AdtSystemSessionFactory.createSystemSessionFactory().createStatefulSession(destination);
      this.monitor = monitor;
   }

   Response get(String path, String accept) throws IOException {
      return send(IRequest.Method.GET, path, accept, null, null);
   }

   Response post(String path, String accept, String contentType, byte[] body) throws IOException {
      return send(IRequest.Method.POST, path, accept, contentType, body);
   }

   Response put(String path, String accept, String contentType, byte[] body) throws IOException {
      return send(IRequest.Method.PUT, path, accept, contentType, body);
   }

   private Response send(IRequest.Method method, String path, String accept, String contentType, byte[] body)
         throws IOException {
      IHeaders headers = HeadersFactory.newHeaders();
      if (accept != null && !accept.isEmpty()) {
         headers.setField(HeadersFactory.newField("Accept", new String[] { accept }));
      }
      IMessageBody messageBody = null;
      if (body != null) {
         messageBody = new ByteArrayMessageBody(contentType == null ? "application/octet-stream" : contentType, body);
      }
      IRequest request = AdtRequestFactory.createRequestFactory()
            .createInstance(method, URI.create(path), headers, messageBody);
      return Response.from(session.sendRequest(monitor, request));
   }

   @Override
   public void close() {
      try {
         if (session.isOpen()) {
            session.close();
         }
      } catch (RuntimeException e) {
         // Sessao ja derrubada do outro lado: reportar isso mascararia o erro
         // real da operacao.
      }
   }

   static final class Response {

      final int status;
      final String body;

      private Response(int status, String body) {
         this.status = status;
         this.body = body;
      }

      boolean ok() {
         return status >= 200 && status < 300;
      }

      static Response from(IResponse response) throws IOException {
         String charsetName = charsetOf(response.getBody() == null ? null : response.getBody().getContentType());
         byte[] raw = readBody(response);
         return new Response(response.getStatus(), new String(raw, Charset.forName(charsetName)));
      }

      private static byte[] readBody(IResponse response) throws IOException {
         if (response.getBody() == null) {
            return new byte[0];
         }
         try (InputStream in = response.getBody().getContent()) {
            if (in == null) {
               return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < MAX_BODY_BYTES) {
               out.write(buffer, 0, read);
            }
            return out.toByteArray();
         }
      }

      private static String charsetOf(String contentType) {
         if (contentType != null) {
            for (String part : contentType.split(";")) {
               String trimmed = part.trim();
               if (trimmed.toLowerCase().startsWith("charset=")) {
                  String name = trimmed.substring("charset=".length()).replace("\"", "").trim();
                  if (Charset.isSupported(name)) {
                     return name;
                  }
               }
            }
         }
         return StandardCharsets.UTF_8.name();
      }
   }
}
