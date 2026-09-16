import io.github.susongyan.redisops.platform.infrastructure.redis.LettuceRedisOperationAdapter;
import io.github.susongyan.redisops.platform.domain.asset.*;
import java.util.List;

/** Only use with a disposable, empty Redis container. */
public class GenericConsoleRedis {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        String endpoint=System.getenv("CONSOLE_TEST_ENDPOINT");
        if(endpoint==null || !endpoint.startsWith("127.0.0.1:"))throw new IllegalArgumentException("Isolated local Redis required");
        var adapter=new LettuceRedisOperationAdapter(id -> new RedisConnectionProfile(id,ClusterMode.STANDALONE,List.of(endpoint),null,null,"NONE",null));
        var ping=adapter.execute(1,0,"PING",List.of(),0);check(ping.success() && "PONG".equals(ping.value()),"PING without old switch");
        var echo=adapter.execute(1,0,"ECHO",List.of("Hello spaces"),0);check(echo.success() && "Hello spaces".equals(echo.value()),"ECHO UTF8 args");
        var write=adapter.execute(1,0,"RPUSH",List.of("console-fixture","one","two"),1);check(write.success(),"RPUSH dynamic args");
        var read=adapter.execute(1,0,"LRANGE",List.of("console-fixture","0","-1"),1);check(read.success() && "[\"one\",\"two\"]".equals(read.value()),"array response");
        var nil=adapter.execute(1,0,"GET",List.of("console-fixture-missing"),1);check(nil.success() && "null".equals(nil.type()),"nil response");
        var nested=adapter.execute(1,0,"COMMAND",List.of("INFO","PING"),0);check(nested.success(),"nested response");
        var unknown=adapter.execute(1,0,"EXAMPLE.UNKNOWN",List.of(),0);check(!unknown.success(),"Redis decides unknown commands");
        var oversized=adapter.execute(1,0,"ECHO",List.of("x".repeat(70000)),0);check(!oversized.success(),"bounded decoded response");
        System.out.println("Generic console Redis smoke passed: scalar, nil, arrays, nested arrays, dynamic command names, limits");
    }
}
