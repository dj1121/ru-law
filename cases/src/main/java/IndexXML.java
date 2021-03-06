import java.io.File;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.*;

// Class to index documents
public class IndexXML implements Runnable {

    private File inputFile;
    private File outputFile;

    public IndexXML(File file) {
        this.inputFile = file;
        this.outputFile = new File(System.getProperty("user.home") + "/Desktop/output.csv");
    }

    public void run() {
        try {
            // Make an entry
            Entry entry = new Entry();

            // Build XML Doc
            Document xmlDoc = buildXMLDoc(inputFile.getAbsolutePath());
            String cdata = null;
            NodeList bodyNodes = xmlDoc.getElementsByTagName("body");
            for (int i = 0; i < bodyNodes.getLength(); i++) {
                Element e = (Element) bodyNodes.item(i);
                cdata = e.getTextContent().trim();
            }

            // Get category and vid_dokumenta
            if(xmlDoc.getElementsByTagName("category").getLength() != 0){
                entry.setCategory(xmlDoc.getElementsByTagName("category").item(0).getTextContent().trim());
            }
            if(xmlDoc.getElementsByTagName("vid_dokumenta").getLength() != 0){
                entry.setVid(xmlDoc.getElementsByTagName("vid_dokumenta").item(0).getTextContent().trim().toLowerCase());
            }

            // Determine if document is of interest (Exclude определения, банкрот, try to get взыскание задолженности)
            if(!entry.getVid().contains("определение")){
                switch(entry.getCategory()) {
                    case "о взыскании задолженности":
                        entry.setCategory("О взыскании задолженности");
                        break;

                    case "о взыскании обязательных платежей":
                        entry.setCategory("О взыскании обязательных платежей");
                        break;
                    case "":
                        if(cdata != null){
                            if (cdata.toLowerCase().contains("о взыскании задолженности") && !cdata.toLowerCase().contains("банкрот")) {
                                entry.setCategory("О взыскании задолженности");
                            } else if (cdata.toLowerCase().contains("о взыскании обязательных платежей") && !cdata.toLowerCase().contains("банкрот")) {
                                entry.setCategory("О взыскании обязательных платежей");
                            } else {
                                return;
                            }
                            break;
                        }
                    default:
                        return;
                }
            }
            else{
                return;
            }

            // Region, case number, judge, date, court, result
            entry.setResult(xmlDoc.getElementsByTagName("result").item(0).getTextContent().trim());
            entry.setCourt(xmlDoc.getElementsByTagName("court").item(0).getTextContent().trim());
            entry.setRegion(xmlDoc.getElementsByTagName("region").item(0).getTextContent().trim());
            entry.setJudge(xmlDoc.getElementsByTagName("judge").item(0).getTextContent().trim());
            entry.setDate(xmlDoc.getElementsByTagName("date").item(0).getTextContent().trim());
            if(xmlDoc.getElementsByTagName("CaseNumber").getLength() != 0){
                entry.setCasenumber(xmlDoc.getElementsByTagName("CaseNumber").item(0).getTextContent().trim());
            }
            if(entry.getJudge() == null || entry.getJudge().equals("")){
                entry.setJudge(getJudge(cdata));
            }

            // Reps
            entry.setPlaintiffreps(getReps(cdata, new String[] {"истца","заявителя", "истец", "заявитель", "представител"}));
            entry.setDefendantreps(getReps(cdata, new String[] {"ответчика", "ответчик"}));

            // Parties
            String[] parties = getParties(cdata, new String[]{"по иск", "заявлен"});
            if(parties != null){
                entry.setPlaintiff(stringCleanup(parties[0]));
                entry.setDefendant(stringCleanup(parties[1]));
            }

            // Financial
            entry.setAmountsought(getRubles(cdata, new String[] {"взыскании","сумме", "размере"}));
            entry.setAmountawarded(getRubles(cdata, new String[] {"взыскании штраф"}));

            // Expedited proceedings
            if(cdata.toLowerCase().contains("упрощенного производства") || cdata.toLowerCase().contains("упрощенное производство")){
                entry.setExpedited("True");
            } else{entry.setExpedited("False");}

            // Breaks
            if(cdata.toLowerCase().contains("объявлялся перерыв")){entry.setBreaks("True");}
            else{entry.setBreaks("False");}

            // Output to CSV
            writeCSV(entry);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // A method to use DOM parser to build XML doc tree
    private Document buildXMLDoc(String docString) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(true);
            factory.setIgnoringElementContentWhitespace(true);
            factory.setValidating(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(docString));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // Get a judge if not found in nodes
    private String getJudge(String cdata){
        String temp = "";
        int index1 = cdata.toLowerCase().indexOf("судья");
        int index2 = cdata.toLowerCase().indexOf("судьи");
        if(index1 >= 0 && index2 >= 0) {
            temp = cdata.substring(Math.min(index1, index2));
        }
        else if(index1 < 0 && index2 >= 0){
            temp = cdata.substring(index2);
        }
        else if(index2 < 0 && index1 >= 0){
            temp = cdata.substring(index1);
        }
        temp = stringCleanup(temp);

        // Look for name with initials
        String[] tempArr = temp.split(" ");
        Pattern pattern1 = Pattern.compile("([А-Я]\\s*\\.\\s*[А-Я]\\s*\\.)\\s*.*");
        for(int i = 0; i < tempArr.length; i++){
            Matcher matcher = pattern1.matcher(tempArr[i]);
            if(matcher.find()){
                return(tempArr[i-1] + " " + matcher.group(1));
            }
        }
        return "";
    }

    // A method to get ruble value from a string
    private String getRubles(String string, String[] keywords){
        // Grab first chunk of text containing keyword and rubles
        for(int i = 0; i < keywords.length; i++){
            String chunk = "";
            int currOcc = string.toLowerCase().indexOf(keywords[i]);
            while(true){
                if(currOcc >= 0) {
                    // If found stop
                    if (string.substring(currOcc, currOcc + 201).contains("руб")){
                        chunk = string.substring(currOcc, currOcc + 201);
                        break;
                    }
                    // If not, keep going
                    else{
                        currOcc = string.toLowerCase().indexOf(keywords[i], currOcc + keywords[i].length());
                    }
                }
                else{
                    break;
                }
            }
            // Start searching in that chunk
            String[] split = chunk.split("\\s+|\\h+");
            String toReturn = "";
            int indexrubles = -1;
            int firstNum = -1;
            int firstNonNum = -1;

            // Find index with word rubles
            for(int j = 0; j < split.length; j++){
                if(split[j].contains("руб")){
                    indexrubles = j;
                    break;
                }
            }
            // Find index with numbers going backward from rubles
            for(int j = indexrubles - 1; j >= 0; j--){
                if(split[j].matches("\\d+.+") || split[j].matches(".+\\d+")){
                    firstNum = j;
                    break;
                }
            }
            // If no numbers found before rubles, numbers must be in same chunk as rubles
            if(firstNum == -1 && indexrubles != -1){
                String[] rubleSplit = split[indexrubles].split("руб");
                toReturn += rubleSplit[0].replaceAll("[^\\d,]|\\.", "").replaceAll(",", ".");
            }
            // Otherwise go on to find index where the first non number occurs
            else{
                for(int j = firstNum - 1; j >= 0; j--){
                    if(!split[j].matches("\\d+.+") && !split[j].matches(".+\\d+") && !split[j].matches("\\d")){
                        firstNonNum = j;
                        break;
                    }
                }
                // Grab things in between non number and number and if ruble index contains numbers, add that to end
                for(int j = firstNonNum + 1; j <= firstNum; j++){
                    toReturn += split[j].replaceAll("[^\\d,]|\\.", "").replaceAll(",", ".");
                }
                if(indexrubles != -1 && split[indexrubles].matches(".*\\d+.*")){
                    String[] rubleSplit = split[indexrubles].split("руб");
                    toReturn += rubleSplit[0].replaceAll("[^\\d]|\\.", "").replaceAll(",", ".");
                }
            }
            // If something found
            if(!toReturn.equals("")){
                return toReturn;
            }
        }
        return "";
    }

    private String[] getParties(String string, String[] keywords){
        // Search with all names, stop once found
        String parties = "";
        string = string.toLowerCase();
        for(String keyword : keywords){
            if(string.contains(keyword)){
               string = string.substring(string.indexOf(keyword));
                if(string.contains("взыскании")){
                    parties = string.substring(0, string.indexOf("взыскании"));
                    break;
                }
                else if(string.contains("признании")){
                    parties = string.substring(0, string.indexOf("признании"));
                    break;
                }
                else{
                    parties = string;
                    break;
                }
            }
        }
        // Split the string into two strings: one before "k" (for the plaintiff) and one after "k" (for the defendant)
        String[] split = null;
        Pattern pattern2 = Pattern.compile("[\\s\\xA0]к[\\s\\xA0]|>к<|<к[\\s\\xA0]|[\\s\\xA0]sк>|>к[\\s\\xA0]|[\\s\\xA0]к<");
        Matcher matcher2 = pattern2.matcher(parties);
        if(matcher2.find()){
           split = parties.split("[\\s\\xA0]к[\\s\\xA0]|>к<|<к[\\s\\xA0]|[\\s\\xA0]к>|>к[\\s\\xA0]|[\\s\\xA0]к<");
        }
        return split;
    }

    // A method to get the reps of the court case
    private ArrayList<String> getReps(String string, String[] possibleNames){
        // List for storing multiples names found
        ArrayList<String> people = new ArrayList<>();

        // For each possible name of the party (until found)
        for(int x = 0; x < possibleNames.length && people.size() == 0; x++){

            // Create target area and clean up
            String temp = string;
            String rep = possibleNames[x];
            temp = stringCleanup(temp);
            if(temp.toLowerCase().contains("при участии")){
                temp = temp.substring(temp.toLowerCase().indexOf("при участии"), temp.toLowerCase().indexOf("при участии") + 1000);
            }
            else if(temp.toLowerCase().contains("в заседании приняли участие")){
                temp = temp.substring(temp.toLowerCase().indexOf("в заседании приняли участие"), temp.toLowerCase().indexOf("в заседании приняли участие") + 1000);
            }
            if(temp.toLowerCase().contains("без вызова сторон")){
                people.add("не явился");
                return people;
            }

            // If the cdata contains the rep name try to grab the first occurrence, otherwise, go to next possible name
            if (temp.toLowerCase().contains(rep)){

                // Make sure to cut before another rep is mentioned
                temp = temp.substring(temp.toLowerCase().indexOf(rep) + rep.length());
                temp = stringCleanup(temp);
                temp = removeOthers(temp);

                // For each line in search region
                String[] lines = temp.split("\\r?\\n");
                for(String line:lines){

                    // Look for initials signifying a rep, add each one
                    String[] tempArr = line.split(" ");
                    Pattern pattern1 = Pattern.compile("([А-Я]\\s*\\.\\s*[А-Я]\\s*\\.)\\s*.*"); // Check for initials at current position
                    Pattern pattern2 = Pattern.compile("[А-Я].*"); // Check that word before or after initials begins with capital letter (hopefully a name)
                    for(int i = 0; i < tempArr.length; i++){
                        Matcher matcher1 = pattern1.matcher(tempArr[i]);
                        if(matcher1.find()){
                            if(i == 0){
                                Matcher matcher2 = pattern2.matcher(tempArr[i+1]);
                                if(matcher2.find()){
                                    people.add(matcher1.group(1) + " " + tempArr[i+1]);
                                }
                            }
                            else{
                                Matcher matcher2 = pattern2.matcher(tempArr[i-1]);
                                if(matcher2.find()){
                                    people.add(tempArr[i-1] + " " + matcher1.group(1));
                                }
                            }
                        }
                    }

                    // Look for full name
                    Pattern pattern3 = Pattern.compile("([А-Я]+[а-я]+\\s[А-Я]+[а-я]+\\s[А-Я]+[а-я]+)");
                    Matcher matcher3 = pattern3.matcher(line);
                    while(matcher3.find()){
                        people.add(matcher3.group(1));
                    }

                    // Look for signifier for not showing up
                    Pattern pattern4 = Pattern.compile("не явился|не явились|представителя не направил");
                    Matcher matcher4 = pattern4.matcher(line);
                    while(matcher4.find()){
                        people.add(matcher4.group(0));
                    }
                }
            }
        }
        return people;
    }

    // A method to cleanup XML before indexing. Handles edge cases, etc.
    private String stringCleanup(String temp){
        // Dashes, brackets, etc.
        temp = temp.replaceAll(",", "");
        temp = temp.replaceAll("&nbsp;", "");
        temp = temp.replaceAll("–", "");
        temp = temp.replaceAll("-", "");
        temp = temp.replaceAll(";", "");
        temp = temp.replaceAll("_", "");
        temp = temp.replaceAll("<[^>]+>|</[^>]+>|<. style[^>]+>|<.+", "");
        temp = temp.replaceAll("[\\s\\xA0]+", " ");
        temp = temp.replaceAll("&quot", " ");
        // Check for ending "o"
        String[] tempSplit = temp.split("[\\s\\xA0]");
        if(tempSplit.length != 0){
            if(tempSplit[tempSplit.length -1].equals("о")){
                temp = "";
                for(int i = 0; i < tempSplit.length - 1; i++){
                    temp += tempSplit[i] + " ";
                }
            }
        }
        temp = temp.trim();
        return temp;
    }

    // Remove other names for parties
    private String removeOthers(String temp){
        if(temp.toLowerCase().contains("ответчик")) temp = temp.substring(0, temp.toLowerCase().indexOf("ответчик"));
        if(temp.toLowerCase().contains("истец")) temp = temp.substring(0, temp.toLowerCase().indexOf("истец"));
        if(temp.toLowerCase().contains("истца")) temp = temp.substring(0, temp.toLowerCase().indexOf("истца"));
        if(temp.toLowerCase().contains("от трет")) temp = temp.substring(0, temp.toLowerCase().indexOf("от трет"));
        if(temp.toLowerCase().contains("от 3-его")) temp = temp.substring(0, temp.toLowerCase().indexOf("3-его"));
        if(temp.toLowerCase().contains("судь")) temp = temp.substring(0, temp.toLowerCase().indexOf("судь"));
        return temp;
    }

    // Printout results for debugging
    private void printout(Entry entry){
        // Printout
        System.out.println("Date: " + entry.getDate());
        System.out.println("Case Number: " + entry.getCasenumber());
        System.out.println("Result: " +entry.getResult());
        System.out.println("Region: " + entry.getRegion());
        System.out.println("Court: " + entry.getCourt());
        System.out.println("Judge: " + entry.getJudge());
        System.out.println("Plaintiff: " + entry.getPlaintiff());
        System.out.println("Plaintiff Reps: " + entry.getPlaintiffreps());
        System.out.println("Defendant: " + entry.getDefendant());
        System.out.println("Defendant Reps: " + entry.getDefendantreps());
        System.out.println("Total amount sought: " + entry.getAmountsought());
        System.out.println("Amount Awarded: " + entry.getAmountawarded());
        System.out.println("Expedited Proceedings: " + entry.getExpedited());
        System.out.println("Breaks: " + entry.getBreaks());
        System.out.println("File: " + inputFile.getName());
        System.out.println();
    }

    // Write results to CSV file
    private void writeCSV(Entry entry){
        try{
            // Write to CSV file
            FileWriter fw = new FileWriter(outputFile, true);
            fw.append(inputFile.getName() + ";");
            fw.append(entry.getDate() + ";");
            fw.append(entry.getCasenumber() + ";");
            fw.append(entry.getResult() + ";");
            fw.append(entry.getRegion() + ";");
            fw.append(entry.getCourt()+ ";");
            fw.append(entry.getJudge() + ";");
            fw.append(entry.getPlaintiff() + ";");
            fw.append(entry.getPlaintiffreps() + ";");
            fw.append(entry.getDefendant() + ";");
            fw.append(entry.getDefendantreps() + ";");
            fw.append(entry.getAmountsought() + ";");
            fw.append(entry.getAmountawarded() + ";");
            fw.append(entry.getBreaks() + ";");
            fw.append(entry.getExpedited() + "\n");
            fw.flush();
            fw.close();
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}

