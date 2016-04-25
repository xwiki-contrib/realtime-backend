package test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;

/**
 * Created by Yann on 25/04/2016.
 */
public class Test {
    public static void main (String [] arg) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ArrayList<Object> Test = new ArrayList<>();
        ArrayList<Object> Test2 = new ArrayList<>();
        Test.add(0);
        Test.add("acudhf");
        Test.add(65);
        Test2.add("1");
        Test.add(Test2);
        System.out.println(mapper.writeValueAsString(Test));
    }
}
