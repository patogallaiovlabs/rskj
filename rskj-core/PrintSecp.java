import java.lang.reflect.Method;
import java.lang.reflect.Field;
public class PrintSecp {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("org.bitcoin.NativeSecp256k1");
        System.out.println("Fields:");
        for (Field f : clazz.getDeclaredFields()) {
            System.out.println(f);
        }
        System.out.println("Methods:");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println(m);
        }
    }
}
