package io.synlabs.synvision.service;

import io.synlabs.synvision.entity.core.Feed;
import io.synlabs.synvision.ex.FeedStreamException;
import io.synlabs.synvision.views.AnnotationRequest;
import io.synlabs.synvision.views.LineSegment;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sun.misc.BASE64Decoder;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.sun.jmx.snmp.EnumRowStatus.destroy;

@Service
public class AnnotationService {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationService.class);

    private Process process;

    public void saveAnnotation(AnnotationRequest request) throws IOException {
        String filename = null;
        Path path= Paths.get("d:/");
        filename = path.resolve(UUID.randomUUID().toString() + ".txt").toString();
        File file = new File(String.valueOf(path));
        if (file.exists()) {
            file.delete();
            //file.renameTo(new File(""));
        }
        FileWriter fw = new FileWriter(filename);
        for (LineSegment lineSegment : request.getLines()) {
            String line = String.format("[x1:%s, y1:%s, x2:%s, y2:%s]\n",
                    lineSegment.getX1(), lineSegment.getY1(), lineSegment.getX2(), lineSegment.getY2());
            fw.write(line);
        }
        logger.info("Annotations written to file");
        fw.close();

        //for converting url to image and saving it.
        //  String sourceData=request.getDataURL();
        //  String[] parts = sourceData.split(",");
        //  String imageString = parts[1];


        //  BufferedImage image = null;
        //  byte[] imageByte;

        //  BASE64Decoder decoder = new BASE64Decoder();
        //  imageByte = decoder.decodeBuffer(imageString);
        //  ByteArrayInputStream bis = new ByteArrayInputStream(imageByte);
        //  image = ImageIO.read(bis);
        //  bis.close();

// wr//te the image to a file
        //  File outputfile = new File("image.png");
        //  if(outputfile.exists())
        //  {
        //      outputfile.delete();
        //  }
        //  ImageIO.write(image, "png", outputfile);
    }

    public void startFeed(Long feedId) {

        File dir = new File("E://LiveFeed");
        String killCommand = "kill -9 $(lsof -t -i:9000)";
        if (SystemUtils.IS_OS_LINUX) {
            try {
                Runtime.getRuntime().exec(killCommand);
            } catch (IOException e) {
                logger.info("Couldn't kill the running process by executing cmd => " + killCommand);
            }
        }
    }
            //For Windows we have to look for alternate code or We can do it manually
            // cmd > netstat -ano | find "9000" - this will return PID
            // taskkill /f /pid PID

        //Runtime.getRuntime().exec("cmd /c start cmd.exe /K " + "ver > Desktop\\output.txt");
      // String s;
      // System.out.println(pro.getOutputStream());
      // BufferedReader stdInput = new BufferedReader(new
      //         InputStreamReader(pro.getInputStream()));
      // while((s=stdInput.readLine())!=null) {
      //     System.out.println(s);
      //  }

       // Feed feed = feedReposoitory.findById(feedId);

   //     try {
   //         process = Runtime.getRuntime().exec("streamer "+feed.getUrl()+" localhost:9000 ", null, dir);
   //     } catch (IOException e) {
   //         throw new FeedStreamException("Couldn't start streaming from feed by command");
   //     }
//
   // }

   //public void stopFeed() throws IOException, InterruptedException {

   //    process.destroy();

   //}
}
