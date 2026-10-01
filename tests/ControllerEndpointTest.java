package io.github.xgl34222220.hetu;

import android.content.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Actual production socket requests to two loopback-only synthetic controller fixtures. */
public final class ControllerEndpointTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();throw new AssertionError("invalid endpoint accepted");}catch(IOException expected){checks++;}}
    private static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();boolean switchDuringRead;
        public Map<String,?> getAll(){Map<String,Object> snapshot=new HashMap<>(values);switchNow();return snapshot;}
        private void switchNow(){if(switchDuringRead){values.put("proxyCustomApiEnabled",false);switchDuringRead=false;}}
        public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
        public String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
        public int getInt(String key,int fallback){int result=(Integer)values.getOrDefault(key,fallback);if(key.equals("proxyCustomApiPort"))switchNow();return result;}
    }
    private static final class Host extends Context {
        final Prefs prefs=new Prefs();
        public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final List<String> requests=new CopyOnWriteArrayList<>();
        final List<String> authorizations=new CopyOnWriteArrayList<>();
        Fixture()throws IOException{
            server.createContext("/",exchange->{
                requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
                authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,2);
                exchange.getResponseBody().write("{}".getBytes());exchange.close();
            });server.start();
        }
        int port(){return server.getAddress().getPort();}
        public void close(){server.stop(0);}
    }
    public static void main(String[] ignored)throws Exception{
        try(Fixture local=new Fixture();Fixture panel=new Fixture()){
            Host context=new Host();Map<String,Object> prefs=context.prefs.values;
            prefs.put("proxyControllerPort",local.port());prefs.put("proxyControllerSecret","synthetic-local-secret");
            prefs.put("proxyCustomApiEnabled",true);prefs.put("proxyCustomApiHost","127.0.0.1");
            prefs.put("proxyCustomApiPort",panel.port());prefs.put("proxyCustomApiSecret","synthetic-panel-secret");
            Map<String,Object> initial=new HashMap<>(prefs);
            MihomoControllerClient root=MihomoControllerClient.forLocalRuntime(context);
            root.reloadConfig("/data/adb/hetu/config.yaml");
            check(local.requests.equals(List.of("PUT /configs?force=true")),"Root reload goes only to local runtime");
            check(local.authorizations.equals(List.of("Bearer synthetic-local-secret")),"Root uses only local authentication");
            check(panel.requests.isEmpty(),"Root did not contact panel endpoint");
            new MihomoControllerClient(context).configs();
            check(panel.requests.equals(List.of("GET /configs")),"panel retains its configured endpoint");
            check(panel.authorizations.get(0).equals("Bearer synthetic-panel-secret"),"panel keeps its own authentication");
            check(prefs.equals(initial),"client never changes API preferences");

            context.prefs.switchDuringRead=true;
            MihomoControllerClient ui=new MihomoControllerClient(context);ui.configs();
            check(panel.requests.size()==2,"in-flight request retains captured panel endpoint");
            check(panel.authorizations.get(1).equals("Bearer synthetic-panel-secret"),"concurrent preference switch cannot send local secret to panel");
            check(local.requests.size()==1,"no cross-endpoint duplicate request");
            ui.configs();
            check(local.requests.size()==2&&local.authorizations.get(1).equals("Bearer synthetic-local-secret"),"next request observes newly selected local endpoint");

            prefs.put("proxyCustomApiEnabled",true);prefs.put("proxyCustomApiHost","https://invalid.example.invalid");
            rejects(ui::configs);check(panel.requests.size()==2&&local.requests.size()==2,"invalid panel host never falls back to another server");
            root.configs();check(local.requests.size()==3,"invalid panel host does not redirect or disable local runtime maintenance");
            prefs.put("proxyCustomApiEnabled","malformed");rejects(ui::configs);
            root.configs();check(local.requests.size()==4,"local runtime ignores unrelated malformed custom selection");

            prefs.put("proxyCustomApiEnabled",true);prefs.put("proxyCustomApiHost","127.0.0.1");
            prefs.remove("proxyControllerSecret");rejects(root::configs);
            check(local.requests.size()==4&&panel.requests.size()==2,"missing local credentials never borrow panel credentials");
            ui.configs();check(panel.requests.size()==3,"panel remains usable independently of local initialization");
            prefs.put("proxyControllerSecret","synthetic-local-secret");prefs.put("proxyCustomApiSecret","invalid\nheader");
            rejects(ui::configs);root.configs();check(local.requests.size()==5,"invalid panel authentication does not affect local maintenance");
            check(local.authorizations.stream().allMatch(x->x.equals("Bearer synthetic-local-secret")),"all local requests keep local secret");
            check(panel.authorizations.stream().allMatch(x->x.equals("Bearer synthetic-panel-secret")),"all panel requests keep panel secret");
            System.out.println("Controller endpoint routing: "+checks+" checks passed; only synthetic loopback servers used");
        }
    }
}
