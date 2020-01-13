package reator;

import app.Config;
import com.mongodb.BasicDBObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Created by sgzhang on Nov 23, 2017.
 * E-mail szhan45@lsu.edu.
 */
public class Util {
    // print runtime info
    static void println(final String str) {
        if (Config.isDebug)
            System.out.println(str);
    }

    public static int[] generateRandomArray (final int n) {
        int[] array = new int[n];
        for (int i = 0; i < n; i++)
            array[i] = i;
        Random random = new Random();
        random.nextInt();
        for (int i = 0; i < n; i++) {
            int change = i + random.nextInt(n-i);
            swap(array, i, change);
        }
        return array;
    }

    private static void swap (int[] array, int i, int change) {
        int tmp = array[i];
        array[i] = array[change];
        array[change] = tmp;
    }

    public static int[] doSelectionSort(int n){
        int[] arr = generateRandomArray(n);
        for (int i = 0; i < arr.length - 1; i++)
        {
            int index = i;
            for (int j = i + 1; j < arr.length; j++)
                if (arr[j] < arr[index])
                    index = j;

            int smallerNumber = arr[index];
            arr[index] = arr[i];
            arr[i] = smallerNumber;
        }
        return arr;
    }

    public static String getAuthors() {
        List<String> queries = new ArrayList<>(Arrays.asList("Zhang", "Wang", "Yang", "Liu", "Li", "Chang", "Chen", "Xin", "Hui", "Ming"));
        Random r = new Random();
        return queries.get(r.nextInt(queries.size()));
    }

    public static String getTitles() {
        List<String> queries = new ArrayList<>(Arrays.asList("graph", "learn", "mean", "performance", "model", "query", "server", "process", "data", "web"));
        Random r = new Random();
        return queries.get(r.nextInt(queries.size()));
    }

    public static BasicDBObject getRegexQuery() {
        BasicDBObject regexQuery = new BasicDBObject();
        BasicDBObject regexAuthor = new BasicDBObject();
        BasicDBObject regexTitle = new BasicDBObject();
        Random r = new Random();
        switch (r.nextInt(3)) {
            case 0:
                regexAuthor.put("$regex", ".*"+getAuthors()+".*");
                regexQuery = new BasicDBObject("author", regexAuthor);
                break;
            case 1:
                regexTitle.put("$regex", ".*"+getTitles()+".*");
                regexQuery = new BasicDBObject("title", regexTitle);
                break;
            case 2:
                regexAuthor.put("$regex", ".*"+getAuthors()+".*");
                regexTitle.put("$regex", ".*"+getTitles()+".*");
                List<BasicDBObject> obj = new ArrayList<>();
                obj.add(new BasicDBObject("author", regexAuthor));
                obj.add(new BasicDBObject("title", regexTitle));
                regexQuery = new BasicDBObject("$or", obj);
                break;
            default:
                regexAuthor.put("$regex", ".*"+getAuthors()+".*");
                regexQuery = new BasicDBObject("author", regexAuthor);
                break;
        }
        return regexQuery;
    }

    public static void main (String[] args) {
        int loopCount = 1;
        long start = System.nanoTime();
        for (int i = 0; i < loopCount; i++)
            doSelectionSort(500);
        long end   = System.nanoTime();
        System.out.println("Time -> "+(end-start)/1000000+" ms");
    }
}
