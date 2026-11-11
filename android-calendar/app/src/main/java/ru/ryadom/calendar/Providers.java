package ru.ryadom.calendar;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.CalendarContract;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;import java.util.concurrent.TimeUnit;
import javax.xml.parsers.*;import org.w3c.dom.*;import org.json.*;import okhttp3.*;
import biweekly.*;import biweekly.component.*;

public class Providers {
 public static class Failure extends Exception {public boolean auth;Failure(String s,boolean a){super(s);auth=a;}}
 static final String DAV="DAV:",CAL="urn:ietf:params:xml:ns:caldav",T="http://schemas.microsoft.com/exchange/services/2006/types",M="http://schemas.microsoft.com/exchange/services/2006/messages";
 static OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
 public static class Result {public List<Event> events=new ArrayList<>();public Map<String,String> calendars=new LinkedHashMap<>();}
 static void validateTarget(String type,String endpoint,String url)throws Failure {
  try {
   if(type.equals("ews")){ExchangeEndpoint.requireTarget(endpoint,url);return;}
   URI target=new URI(url);
   if(!type.equals("yandex")||!"https".equals(target.getScheme())||!"caldav.yandex.ru".equals(target.getHost())||target.getRawUserInfo()!=null||target.getRawFragment()!=null||(target.getPort()!=-1&&target.getPort()!=443)||url.contains("\\"))throw new IllegalArgumentException();
  }catch(Exception e){throw new Failure(type.equals("ews")?"Укажите HTTPS-адрес Exchange в «Войти снова». Запрос разрешён только на настроенный сервер.":"Сервер вернул недопустимый адрес.",true);}
 }
 static Request.Builder authenticatedRequest(String type,String endpoint,String url,String login,String password)throws Failure {
  validateTarget(type,endpoint,url);
  return new Request.Builder().url(url).header("Authorization",Credentials.basic(login,password,StandardCharsets.UTF_8));
 }
 static Document request(JSONObject a,String method,String url,String body,String depth)throws Exception {
  Request.Builder request=authenticatedRequest(a.getString("type"),a.optString("endpoint"),url,a.getString("login"),a.getString("password")).header("User-Agent","RyadomAndroid/0.1").method(method,RequestBody.create(body,MediaType.get("text/xml; charset=utf-8")));
  if(depth!=null)request.header("Depth",depth);if(method.equals("POST"))request.header("SOAPAction","\""+M+(soapAction(body))+"\"");
  try(Response response=(method.equals("POST")?HTTP.newBuilder().retryOnConnectionFailure(false).build():HTTP).newCall(request.build()).execute()){
   int status=response.code();if(status==401||status==403)throw new Failure("HTTP "+status+": вход отклонён. Проверьте учётные данные; автопопытки остановлены.",true);
   if(status>=300&&status<400)throw new Failure("Сервер требует перехода на другую страницу. Пароль не переслан.",true);
   if(!response.isSuccessful())throw new Failure("Сервер вернул HTTP "+status+". Повторим позже.",false);
   ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=response.body().byteStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>16*1024*1024)throw new Failure("Слишком большой ответ календаря.",false);}}
   String xml=out.toString("UTF-8");if(xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new Failure("Неподдерживаемый XML.",false);
   DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);DocumentBuilder parser=f.newDocumentBuilder();parser.setEntityResolver((p,s)->new org.xml.sax.InputSource(new StringReader("")));return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
 }
 static String soapAction(String body){for(String action:new String[]{"CreateItem","UpdateItem","DeleteItem","GetItem","FindItem"})if(body.contains("<m:"+action))return "/"+action;throw new IllegalArgumentException("Unknown SOAP operation");}
 static Event ewsEvent(Element item,String source){Event e=new Event();Element id=elements(item,T,"ItemId").get(0);e.id=id.getAttribute("Id");e.etag=id.getAttribute("ChangeKey");e.stableId=e.id;e.source=source;e.calendar="main";e.title=val(item,T,"Subject");e.location=val(item,T,"Location");e.description=val(item,T,"Body");e.start=parseTime(val(item,T,"Start"));e.end=parseTime(val(item,T,"End"));e.allDay=val(item,T,"IsAllDayEvent").equalsIgnoreCase("true");e.cancelled=val(item,T,"IsCancelled").equalsIgnoreCase("true");String kind=val(item,T,"CalendarItemType");e.recurring=!kind.isEmpty()&&!kind.equals("Single");String reminder=val(item,T,"ReminderIsSet");if(!reminder.isEmpty())e.reminderMinutes=reminder.equalsIgnoreCase("true")?Integer.parseInt(val(item,T,"ReminderMinutesBeforeStart")):-1;for(Element prop:elements(item,T,"ExtendedProperty")){List<Element> field=elements(prop,T,"ExtendedFieldURI");if(!field.isEmpty()&&"PekloToken".equals(field.get(0).getAttribute("PropertyName"))){String token=val(prop,T,"Value");if(token.startsWith("copy-"))e.copyToken=token;}}return e;}
 static String val(Element e,String ns,String name){NodeList list=e.getElementsByTagNameNS(ns,name);return list.getLength()==0?"":list.item(0).getTextContent();}
 static List<Element> elements(Element e,String ns,String name){List<Element> out=new ArrayList<>();NodeList l=e.getElementsByTagNameNS(ns,name);for(int i=0;i<l.getLength();i++)out.add((Element)l.item(i));return out;}
 static String iso(long time){return Instant.ofEpochMilli(time).toString();}
 static long parseTime(String s){return OffsetDateTime.parse(s).toInstant().toEpochMilli();}
 static String href(String base,String href)throws Exception {return new URI(base).resolve(href).toString();}
 static List<Element> props(Document doc){List<Element> result=new ArrayList<>();for(Element ps:elements(doc.getDocumentElement(),DAV,"propstat")){if(val(ps,DAV,"status").contains(" 200 "))result.addAll(elements(ps,DAV,"prop"));}return result;}
 public static Result load(Context c,JSONObject a,long from,long to)throws Exception {
  return switch(a.getString("type")){case "ews" -> ews(a,from,to);case "yandex" -> yandex(a,from,to);case "device" -> device(c,a,from,to);default -> throw new Failure("Неизвестный источник",true);};
 }
 static Result ews(JSONObject a,long from,long to)throws Exception {
  String endpoint;try{endpoint=ExchangeEndpoint.normalize(a.optString("endpoint"));}catch(IllegalArgumentException e){throw new Failure("Укажите HTTPS-адрес Exchange в «Войти снова».",true);}
  Result result=new Result();result.calendars.put("main","Работа");
  for(long cursor=from;cursor<to;cursor+=7*86400000L){long end=Math.min(to,cursor+7*86400000L);
   String fields="";for(String f:new String[]{"item:Subject","calendar:Start","calendar:End","calendar:IsAllDayEvent","calendar:IsCancelled","calendar:Location","calendar:CalendarItemType","item:ReminderIsSet","item:ReminderMinutesBeforeStart"})fields+="<t:FieldURI FieldURI=\""+f+"\"/>";
   String xml="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:m=\""+M+"\" xmlns:t=\""+T+"\"><s:Header><t:RequestServerVersion Version=\"Exchange2010_SP2\"/></s:Header><s:Body><m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>"+fields+EventWrites.TOKEN_FIELD+"</t:AdditionalProperties></m:ItemShape><m:CalendarView MaxEntriesReturned=\"1000\" StartDate=\""+iso(cursor)+"\" EndDate=\""+iso(end)+"\"/><m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem></s:Body></s:Envelope>";
   Document doc=request(a,"POST",endpoint,xml,null);List<Element> messages=elements(doc.getDocumentElement(),M,"FindItemResponseMessage");if(messages.isEmpty())throw new Failure("Exchange не вернул календарь.",false);
   for(Element msg:messages){String code=val(msg,M,"ResponseCode");if(!code.equals("NoError"))throw new Failure("Exchange: "+(code.matches("[A-Za-z0-9]{1,80}")?code:"ошибка запроса"),code.equals("ErrorAccessDenied"));for(Element folder:elements(msg,M,"RootFolder"))if(folder.getAttribute("IncludesLastItemInRange").equals("false"))throw new Failure("Неполный календарь: слишком много встреч за неделю.",false);}
   for(Element item:elements(doc.getDocumentElement(),T,"CalendarItem")){Event e=ewsEvent(item,a.getString("id"));result.events.add(e);}
  }
  Map<String,Event> unique=new LinkedHashMap<>();for(Event e:result.events)unique.put(e.id,e);result.events=new ArrayList<>(unique.values());return result;
 }
 static Document prop(JSONObject a,String url,String fields,String depth)throws Exception{return request(a,"PROPFIND",url,"<d:propfind xmlns:d=\"DAV:\" xmlns:c=\""+CAL+"\"><d:prop>"+fields+"</d:prop></d:propfind>",depth);}
 static Result yandex(JSONObject a,long from,long to)throws Exception {
  String base="https://caldav.yandex.ru/",principal="",home="";
  for(Element p:props(prop(a,base,"<d:current-user-principal/>","0")))for(Element q:elements(p,DAV,"current-user-principal"))principal=val(q,DAV,"href");
  if(principal.isEmpty())throw new Failure("Яндекс не вернул пользователя CalDAV.",false);
  for(Element p:props(prop(a,href(base,principal),"<c:calendar-home-set/>","0")))for(Element q:elements(p,CAL,"calendar-home-set"))home=val(q,DAV,"href");
  if(home.isEmpty())throw new Failure("Яндекс не вернул календари.",false);home=href(base,home);
  Document listing=prop(a,home,"<d:displayname/><d:resourcetype/>","1");Result result=new Result();DateTimeFormatter fmt=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);String f=fmt.format(Instant.ofEpochMilli(from)),t=fmt.format(Instant.ofEpochMilli(to));
  for(Element response:elements(listing.getDocumentElement(),DAV,"response")){
   if(elements(response,CAL,"calendar").isEmpty())continue;String url=href(home,val(response,DAV,"href")),calId=url;result.calendars.put(calId,val(response,DAV,"displayname"));
   String query="<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\""+CAL+"\"><d:prop><d:getetag/><c:calendar-data><c:expand start=\""+f+"\" end=\""+t+"\"/></c:calendar-data></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\"><c:time-range start=\""+f+"\" end=\""+t+"\"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>";
   Document calendar=request(a,"REPORT",url,query,"1");for(Element responseEvent:elements(calendar.getDocumentElement(),DAV,"response"))for(Element data:elements(responseEvent,CAL,"calendar-data"))for(Event e:parseIcs(data.getTextContent(),a.getString("id"),calId,from,to)){e.remoteUri=href(url,val(responseEvent,DAV,"href"));e.etag=val(responseEvent,DAV,"getetag");result.events.add(e);}
  }return result;
 }
 static List<Event> parseIcs(String raw,String source,String cal,long from,long to)throws Exception {
  List<Event> out=new ArrayList<>();for(ICalendar calendar:Biweekly.parse(raw).all()){
   Map<String,VEvent> overrides=new HashMap<>();for(VEvent v:calendar.getEvents())if(v.getRecurrenceId()!=null)overrides.put((v.getUid()==null?"":v.getUid().getValue())+"|"+v.getRecurrenceId().getValue().getTime(),v);
   for(VEvent v:calendar.getEvents()){
    if(v.getDateStart()==null)continue;long start=v.getDateStart().getValue().getTime();long duration=v.getDateEnd()!=null?v.getDateEnd().getValue().getTime()-start:v.getDuration()!=null?v.getDuration().getValue().toMillis():v.getDateStart().getValue().hasTime()?0:86400000L;
    String uid=v.getUid()==null?String.valueOf(start):v.getUid().getValue();List<Long> starts=new ArrayList<>();
    if(v.getRecurrenceId()!=null||v.getRecurrenceRule()==null){starts.add(start);}else{
     TimeZone zone=TimeZone.getTimeZone("Europe/Moscow");biweekly.io.TimezoneInfo info=calendar.getTimezoneInfo();biweekly.io.TimezoneAssignment assign=info.getTimezone(v.getDateStart());if(assign!=null)zone=assign.getTimeZone();
     biweekly.util.com.google.ical.compat.javautil.DateIterator it=v.getDateIterator(zone);it.advanceTo(new Date(from-Math.max(0,duration)));int limit=0;while(it.hasNext()&&limit++<10000){long s=it.next().getTime();if(s>=to)break;if(!overrides.containsKey(uid+"|"+s))starts.add(s);}if(limit>=10000)throw new Failure("Слишком много повторений календаря.",false);
    }
    for(long s:starts){if(s>=to||s+duration<from)continue;Event e=new Event();long identity=v.getRecurrenceId()!=null?v.getRecurrenceId().getValue().getTime():s;e.id=cal+"|"+uid+"|"+identity;e.source=source;e.calendar=cal;e.recurring=v.getRecurrenceId()!=null||v.getRecurrenceRule()!=null;e.stableId=cal+"|"+uid+(e.recurring?"|"+identity:"");e.description=v.getDescription()==null?"":v.getDescription().getValue();e.reminderMinutes=-1;for(VAlarm alarm:v.getAlarms())if(alarm.getTrigger()!=null&&alarm.getTrigger().getDuration()!=null){long offset=alarm.getTrigger().getDuration().toMillis();if(offset<=0)e.reminderMinutes=(int)Math.min(525600,-offset/60000);}if(v.getExperimentalProperty("X-PEKLO-TOKEN")!=null){String token=v.getExperimentalProperty("X-PEKLO-TOKEN").getValue();if(token.startsWith("copy-"))e.copyToken=token;}e.title=v.getSummary()==null?"Без названия":v.getSummary().getValue();e.location=v.getLocation()==null?"":v.getLocation().getValue();e.start=s;e.end=s+Math.max(0,duration);e.allDay=!v.getDateStart().getValue().hasTime();e.cancelled=v.getStatus()!=null&&v.getStatus().getValue().equalsIgnoreCase("CANCELLED");out.add(e);}
   }
  }return out;
 }
 static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 static boolean deleted(Context c,JSONObject account,Event before)throws Exception {
  String type=account.optString("type");
  if("device".equals(type)){String id=before.id.substring(0,before.id.lastIndexOf('|'));try(Cursor row=c.getContentResolver().query(CalendarContract.Events.CONTENT_URI,new String[]{"deleted","eventStatus"},"_id=?",new String[]{id},null)){if(row==null)throw new Failure("Не удалось проверить удаление встречи.",false);return !row.moveToFirst()||row.getInt(0)!=0||row.getInt(1)==CalendarContract.Events.STATUS_CANCELED;}}
  if("ews".equals(type)){String endpoint=ExchangeEndpoint.normalize(account.optString("endpoint"));String body="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:m=\""+M+"\" xmlns:t=\""+T+"\"><s:Header><t:RequestServerVersion Version=\"Exchange2010_SP2\"/></s:Header><s:Body><m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds><t:ItemId Id=\""+xml(before.id)+"\"/></m:ItemIds></m:GetItem></s:Body></s:Envelope>";Document doc=request(account,"POST",endpoint,body,null);String code=val(doc.getDocumentElement(),M,"ResponseCode");if("ErrorItemNotFound".equals(code))return true;if("NoError".equals(code))return false;throw new Failure("Не удалось проверить удаление встречи Exchange.",false);}
  if("yandex".equals(type)){String uid=before.id.substring(before.calendar.length()+1,before.id.lastIndexOf('|'));String body="<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\""+CAL+"\"><d:prop><d:getetag/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\"><c:prop-filter name=\"UID\"><c:text-match collation=\"i;octet\">"+xml(uid)+"</c:text-match></c:prop-filter></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>";Document doc=request(account,"REPORT",before.calendar,body,"1");if(!"multistatus".equals(doc.getDocumentElement().getLocalName()))throw new Failure("Не удалось проверить удаление встречи CalDAV.",false);return elements(doc.getDocumentElement(),DAV,"response").isEmpty();}
  return false;
 }
 static void deviceDetails(Context c,Event e,String id){try(Cursor row=c.getContentResolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,Long.parseLong(id)),new String[]{"description","rrule","original_id","originalInstanceTime","customAppUri"},null,null,null)){if(row!=null&&row.moveToFirst()){e.description=row.isNull(0)?"":row.getString(0);e.recurring=!row.isNull(1)||!row.isNull(2);e.stableId=e.recurring?(row.isNull(2)?id:row.getString(2))+"|"+(row.isNull(3)?e.start:row.getLong(3)):id;String marker=row.isNull(4)?"":row.getString(4);if(marker.startsWith("peklo:copy-"))e.copyToken=marker.substring(6);}}e.reminderMinutes=-1;try(Cursor reminders=c.getContentResolver().query(CalendarContract.Reminders.CONTENT_URI,new String[]{"minutes"},"event_id=?",new String[]{id},"minutes ASC")){if(reminders!=null&&reminders.moveToFirst())e.reminderMinutes=Math.max(0,reminders.getInt(0));}}
 static Result device(Context c,JSONObject a,long from,long to)throws Exception {
  if(c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new Failure("Разрешите чтение календарей телефона.",true);
  Result result=new Result();JSONArray selected=a.optJSONArray("calendarIds");Set<String> ids=new HashSet<>();if(selected!=null)for(int i=0;i<selected.length();i++)ids.add(selected.optString(i));
  try(Cursor calendars=c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,new String[]{"_id","calendar_displayName"},null,null,null)){if(calendars==null)throw new Failure("Системный список календарей недоступен.",false);while(calendars.moveToNext())if(ids.contains(calendars.getString(0))&&!DeviceWrites.exported(c,calendars.getString(0)))result.calendars.put(calendars.getString(0),calendars.getString(1));}
  Uri.Builder uri=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,from);ContentUris.appendId(uri,to);
  try(Cursor cursor=c.getContentResolver().query(uri.build(),new String[]{"event_id","begin","end","title","eventLocation","allDay","calendar_id","eventStatus"},null,null,"begin ASC")){if(cursor==null)throw new Failure("Системные события недоступны.",false);while(cursor.moveToNext()){String cal=cursor.getString(6);if(!result.calendars.containsKey(cal))continue;Event e=new Event();e.id=cursor.getString(0)+"|"+cursor.getLong(1);e.source=a.getString("id");e.calendar=cal;e.title=cursor.isNull(3)?"Без названия":cursor.getString(3);e.location=cursor.isNull(4)?"":cursor.getString(4);e.start=cursor.getLong(1);e.end=cursor.getLong(2);e.allDay=cursor.getInt(5)!=0;e.cancelled=cursor.getInt(7)==CalendarContract.Events.STATUS_CANCELED;if(e.allDay){e.start=Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(Event.ZONE).toInstant().toEpochMilli();e.end=Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(Event.ZONE).toInstant().toEpochMilli();}deviceDetails(c,e,cursor.getString(0));result.events.add(e);}}
  return result;
 }
}
