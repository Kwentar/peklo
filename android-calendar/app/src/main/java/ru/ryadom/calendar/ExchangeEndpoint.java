package ru.ryadom.calendar;
import java.net.URI;
import java.util.Locale;

public final class ExchangeEndpoint {
 private ExchangeEndpoint() {}
 public static String normalize(String value) {
  String message="Укажите HTTPS-адрес Exchange: https://mail.example.org/EWS/Exchange.asmx (порт 443).";
  try {
   if(value==null)throw new IllegalArgumentException();
   value=value.trim();
   if(value.isEmpty()||value.matches(".*[\\\\\\s\\x00-\\x1f\\x7f].*"))throw new IllegalArgumentException();
   URI u=new URI(value);String path=u.getPath();
   if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getHost().matches("[A-Za-z0-9.-]+")||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443)||u.getRawAuthority().contains("%")||u.getRawPath().contains("%")||!(path.isEmpty()||path.equals("/")||path.equalsIgnoreCase("/EWS/Exchange.asmx")))throw new IllegalArgumentException();
   return "https://"+u.getHost().toLowerCase(Locale.ROOT)+"/EWS/Exchange.asmx";
  }catch(Exception e){throw new IllegalArgumentException(message);}
 }
 public static void requireTarget(String configured,String target) {
  if(!normalize(configured).equals(target))throw new IllegalArgumentException("Адрес запроса отличается от настроенного Exchange. Учётные данные не отправлены.");
 }
}
