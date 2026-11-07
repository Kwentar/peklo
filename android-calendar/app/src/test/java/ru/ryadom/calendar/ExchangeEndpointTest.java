package ru.ryadom.calendar;
import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.Request;
import okhttp3.Credentials;
import java.nio.charset.StandardCharsets;
public class ExchangeEndpointTest {
 @Test public void configuredServer() {
  for(String s:new String[]{"https://mail.example.org","https://MAIL.example.org:443/","https://mail.example.org/ews/exchange.asmx"})assertEquals("https://mail.example.org/EWS/Exchange.asmx",ExchangeEndpoint.normalize(s));
 }
 @Test public void rejectsUnsafeAddresses() {
  for(String s:new String[]{null,"","mail.example.org","http://mail.example.org","https://u:p@mail.example.org","https://mail.example.org:8443","https://mail.example.org/?token=test","https://mail.example.org/#fragment","https://mail.example.org/other","https://mail.example.org\\@other.example.org","https://mail%2eexample.org","https://mail.example.org/EWS/%45xchange.asmx","https://mail.example.org/\nEWS/Exchange.asmx"})assertThrows(IllegalArgumentException.class,()->ExchangeEndpoint.normalize(s));
 }
 @Test public void credentialsOnlyForConfiguredTarget() throws Exception {
  String endpoint=ExchangeEndpoint.normalize("https://mail.example.org");
  Request request=Providers.authenticatedRequest("ews",endpoint,endpoint,"test-user","test-password").build();
  assertEquals(endpoint,request.url().toString());assertEquals(Credentials.basic("test-user","test-password",StandardCharsets.UTF_8),request.header("Authorization"));
  for(String target:new String[]{"https://other.example.org/EWS/Exchange.asmx","http://mail.example.org/EWS/Exchange.asmx","https://mail.example.org/owa/","https://mail.example.org:8443/EWS/Exchange.asmx","https://mail.example.org/EWS/Exchange.asmx?test=1"})assertThrows(Providers.Failure.class,()->Providers.authenticatedRequest("ews",endpoint,target,"test-user","test-password"));
  assertThrows(Providers.Failure.class,()->Providers.authenticatedRequest("ews","",endpoint,"test-user","test-password"));
 }
 @Test public void redirectsAreDisabledAndYandexRemainsPinned() throws Exception {
  assertFalse(Providers.HTTP.followRedirects());assertFalse(Providers.HTTP.followSslRedirects());
  Providers.validateTarget("yandex","","https://caldav.yandex.ru/calendars/test/");
  assertThrows(Providers.Failure.class,()->Providers.authenticatedRequest("yandex","","https://other.example.org/","test-user","test-password"));
 }
}
