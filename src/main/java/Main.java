
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import ij.IJ;
import ij.ImagePlus;
import org.apache.commons.lang3.tuple.*;

public class Main {

    private static int [] channelOffsets = new int[]{0,1,1,1};

    private static final int maxCycle = 21;
    private static final int maxCh = 4;
    private static final int maxZ = 21;

    public static void main(String [] args) throws Exception{

        String sourceDir = args[0];

        Path destDir =  Paths.get(args[1]);

        PrintWriter errors = new PrintWriter(new FileWriter(destDir.resolve("failedTiles.log").toFile()));

        PrintWriter tileMap = new PrintWriter(new FileWriter(destDir.resolve("processed").resolve("tileMap.txt").toFile()));

        int keepZSlizes = Integer.parseInt(args[2]);

        final int mt = (args.length>3)?Integer.parseInt(args[3]):4;

        final int startingRegion = (args.length>4)?Integer.parseInt(args[4]):1;

        System.out.println("sourceDir = " + sourceDir);

        System.out.println("destDir = " + destDir);

        File mapFile = new File(destDir+"/"+ "ConcatenationMap.txt");

        System.out.println("keepZSlizes = " + Integer.parseInt(args[2]));

        if(keepZSlizes%2==0){
            throw new IllegalArgumentException("Number of Z-slices to keep must be odd");
        }

        List<Path> dirs = Files.find(Paths.get(sourceDir),
                2,
                (filePath, fileAttr) -> filePath.toFile().getName().equals("processed") && filePath.toFile().isDirectory()).map(p->p.getParent()).collect(Collectors.toList());

        System.out.println(dirs.toString().replace(",","\t"));

        Map<Path, List<Path>> expTiles =
                dirs.stream().flatMap(p->{
                    try{
                        return Files.find(p.resolve("processed").resolve("tiles"),
                                1,
                                (filePath, fileAttr) -> filePath.toFile().getName().startsWith("reg") && filePath.toFile().isDirectory());
                    }catch ( Exception e){
                        e.printStackTrace();
                        return null;
                    }
                }).collect(Collectors.groupingBy(p->p.getParent().getParent().getParent()));

        Map<Path, Map<Integer, List<Path>>> expRegTiles = new HashMap<>();

        expTiles.keySet().stream().forEach((k)->{
            expRegTiles.put(k, expTiles.get(k).stream().collect(Collectors.groupingBy(p->Integer.parseInt(p.getFileName().toString().substring(3,6)))));
        });

        mapFile.getParentFile().mkdirs();
        PrintWriter bw = new PrintWriter(new FileWriter(mapFile));

        bw.println("New Region Index\tOld Experiment Folder\tOld Region Index\tWidth_in_Tiles\tHeight_in_Tiles\tTile folder paths");
        AtomicInteger newRegIdx = new AtomicInteger(0);

        expRegTiles.entrySet().stream().sorted(Comparator.comparing(o->o.getKey())).forEach(exp-> {
            System.out.println(exp.getKey());

            Path bestFocusTemp = exp.getKey().resolve("processed").resolve("bestFocus");

            if (!Files.exists(bestFocusTemp)) {
                bestFocusTemp = exp.getKey().resolve("processed").resolve("tiles").resolve("bestFocus");
            }
            final Path bestFocus = bestFocusTemp;
            if (!Files.exists(bestFocus)) {
                throw new IllegalStateException("bestFocus folder not found");
            }
        });


        tileMap.println("RegionNumber\tTileX\tTileY\tXposition\tYposition");


        expRegTiles.entrySet().stream().sorted(Comparator.comparing(o->o.getKey())).forEach(exp->{
            System.out.println("Working on experiment:" + exp.getKey());

            Path bestFocusTemp = exp.getKey().resolve("processed").resolve("bestFocus");

            if (!Files.exists(bestFocusTemp)) {
                bestFocusTemp = exp.getKey().resolve("processed").resolve("tiles").resolve("bestFocus");
            }
            final Path bestFocus = bestFocusTemp;

            exp.getValue().entrySet().stream().sorted(Comparator.comparing(o->o.getKey())).forEach(region->{
                Collections.sort(region.getValue());
                final int newRegIDX = newRegIdx.incrementAndGet();

                List<Path> trunkRegPaths = region.getValue().stream().map(s->s.subpath(s.getNameCount()-3, s.getNameCount())).collect(Collectors.toList());

                int maxYValue = trunkRegPaths.stream().map(s->s.getFileName().toString()).map(s->s.substring(s.lastIndexOf("_Y")+2,s.lastIndexOf("_Y")+4)).map(s->Integer.parseInt(s)).max(Comparator.naturalOrder()).get();

                int maxXValue = trunkRegPaths.stream().map(s->s.getFileName().toString()).map(s->s.substring(s.lastIndexOf("_X")+2,s.lastIndexOf("_X")+4)).map(s->Integer.parseInt(s)).max(Comparator.naturalOrder()).get();


                String out = "\t"+ newRegIDX +"\t" + exp.getKey().getFileName() + "\t" + region.getKey() + "\t" + maxXValue  + "\t" + maxYValue + "\t" + trunkRegPaths;
                System.out.println(out);
                bw.println(out);
                bw.flush();


                if(true || newRegIDX < startingRegion){
                    System.out.println("Skipping region: " + newRegIDX);

                }else {
                    System.out.println("Copying region: " + newRegIDX);
                    region.getValue().forEach(tileFolder -> {
                        String oldRegSubstring = String.format("reg%03d", region.getKey());
                        String newRegSubstring = String.format("reg%03d", newRegIDX);
                        Path newTileDir = destDir.resolve(tileFolder.subpath(tileFolder.getNameCount() - 3, tileFolder.getNameCount())).resolveSibling(tileFolder.getFileName().toString().replace(oldRegSubstring, newRegSubstring));
                        try {
                            Files.createDirectories(newTileDir);
                            System.out.println("Dir created:" + newTileDir);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        System.out.println("Working on a dir: " + tileFolder.toString());

                        try {
                            System.out.println("Copying files: " + tileFolder);
                            copyZStackSubset(tileFolder, newTileDir, keepZSlizes, bestFocus, mt, oldRegSubstring, newRegSubstring);
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.println("Error copying tile folder:" + tileFolder);
                            e.printStackTrace(errors);
                            errors.println();
                            errors.flush();
                        }
                    });

                    bw.flush();
                    bw.close();

                }

                //Checking missing folders
                boolean missingFolders = false;
                for (int i = 1; i <= maxXValue; i++) {
                    for (int j = 1; j <= maxYValue ; j++) {
                        Path currTileDir = destDir.resolve("processed").resolve("tiles").resolve(String.format("reg%03d_X%02d_Y%02d", newRegIDX, i, j));


                        if(!Files.exists(currTileDir)){
                            errors.println("Error: missing tile folder:"+ currTileDir);
                            errors.flush();
                            missingFolders=true;
                        }

                        try{
                            Optional<Path> img = Files.find(currTileDir, 1,  (filePath, fileAttr) ->  filePath.getFileName().toString().endsWith(".tif")).findFirst();
                            if(!img.isPresent()){
                                errors.println("Error: empty tile folder:"+ currTileDir);
                                errors.flush();

                                missingFolders=true;
                            }
                        }catch (IOException e){
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }

                if(missingFolders){
                    errors.close();
                    throw new IllegalStateException("There are missing folders. Cannot continue. Check the failedTiles.log in the destination directory");
                }

                int xOffset = 0;
                int yOffset = 0;
                for (int i = 1; i <= maxXValue; i++) {
                    int maxTileH = 0;
                    for (int j = 1; j <= maxYValue ; j++) {
                        Path currTileDir =  destDir.resolve("processed").resolve("tiles").resolve(String.format("reg%03d_X%02d_Y%02d", newRegIDX, i, j));
                        try{
                            Optional<Path> img = Files.find(currTileDir, 1,  (filePath, fileAttr) ->  filePath.getFileName().toString().endsWith(".tif")).findFirst();
                            if(img.isPresent()){
                                ImagePlus imp = IJ.openImage(img.get().toString());
                                int w = imp.getWidth();
                                int h = imp.getHeight();

                                maxTileH = Math.max(h,maxTileH);

                                tileMap.println(String.format("%d\t%d\t%d\t%d\t%d", newRegIDX, i, j, xOffset,yOffset));
                                tileMap.flush();
                                xOffset += w;
                            }else{
                                errors.println("Error: empty tile folder:"+ currTileDir);
                                errors.flush();
                                throw new IllegalStateException("Error: empty tile folder:"+ currTileDir);
                            }
                        }catch (IOException e){
                            throw new IllegalStateException(e);
                        }
                    }
                    yOffset+=maxTileH;
                    xOffset=0;
                }
            });
        });

        tileMap.flush();
        tileMap.close();
        errors.flush();
        errors.close();

        System.exit(0);
    }

    private static int findBestZMaxIntensity(Path tileFolder, int bandwidth, int cycle) {
        try {
        Pair<Integer, BufferedImage>[] list = Files.find(tileFolder, 1, (filePath, fileAttr) -> {
                String name = filePath.getFileName().toString();
                return name.contains("_c001") && name.contains(String.format("_t%03d", cycle));
        }
        ).map(p->{
            try{
                return (Pair<Integer, BufferedImage>)Pair.of(Integer.parseInt(p.getFileName().toString().split("_z")[1].substring(0,3)),ImageIO.read(p.toFile()));
            }catch (Exception e){
                try {
                    return (Pair<Integer, BufferedImage>) Pair.of(Integer.parseInt(p.getFileName().toString().split("_z")[1].substring(0, 3)), ImageIO.read(p.toFile()));
                }catch (Exception e2) {
                    e2.printStackTrace();
                    return (Pair<Integer, BufferedImage>) Pair.of(Integer.parseInt(p.getFileName().toString().split("_z")[1].substring(0, 3)), (BufferedImage) null);
                }
            }}).sorted(Comparator.comparing(p->p.getLeft())).toArray(Pair[]::new);
        Pair<Integer, Double>[] means = Arrays.stream(list).map(p->
            Pair.of(p.getLeft(), new ImagePlus("zSlice"+p.getLeft(), p.getValue()).getStatistics().mean)
        ).sorted(Comparator.comparing(p->-p.getValue())).toArray(Pair[]::new);

        int step = bandwidth/2;

        double maxIntens = 0;
        int maxIndex = -1;

        for (int i = 0; i < means.length; i++) {
            int sumW = 0;
            double sumIntens = 0;
            for (int j = i-step; j <= i+step; j++) {
                if(j<0||j>=means.length) continue;
                double w = (step - Math.abs(j-i));
                sumIntens+=means[j].getValue()*w;
                sumW+=w;
            }
            double avgIntens = sumIntens/sumW;
            if(avgIntens>maxIntens){
                maxIntens=avgIntens;
                maxIndex=means[i].getKey();
            }
        }
        return maxIndex;

        }catch (IOException ex){
            ex.printStackTrace();
            return -1;
        }
    }

    private static void copyZStackSubset(Path tileFolder, Path destTileFolder, int keepZSlizes, Path bestFocusFolder, int numThreads, String oldRegSubstring, String newRegSubstring){

        try{

            ExecutorService es_copy = Executors.newFixedThreadPool(numThreads);

            Optional<Path>  o = Files.find(bestFocusFolder,1,(filePath, fileAttr) ->  filePath.getFileName().toString().startsWith(tileFolder.toFile().getName())).findFirst();
            if(o.isPresent()){
                System.out.println("bestFocus tiff found:"+ o.get().getFileName().toString());


                for (int cyc = 1; cyc <= maxCycle; cyc++) {
                    int bestZ = findBestZMaxIntensity(tileFolder, keepZSlizes, cyc);


                    int range = keepZSlizes/2;

                    if(bestZ-range<1){
                        bestZ=range+1;
                    }
                    if(bestZ+range>maxZ){
                        bestZ = maxZ-range;
                    }


                    for (int ch = 1; ch < maxCh; ch++) {

                        final int channel = ch;
                        final int cycle = cyc;
                        final int correctedBestZ = bestZ + channelOffsets[ch-1];

                        int zOffset = (correctedBestZ-range)-1;


                        Map<Integer, List<Path>> mapByZ = Files.find(tileFolder, 1, (filePath, fileAttr) -> {
                            String name = filePath.getFileName().toString();
                            return name.contains(String.format("_t%03d",cycle))&name.contains(String.format("_c%03d",channel)) & name.endsWith(".tif");
                        }).collect(Collectors.groupingBy(
                                f->Integer.parseInt(f.getFileName().toString().split("_z")[1].substring(0,3))
                        ));

                        List<Runnable> renamingTasks = new ArrayList<>();

                        for (int i = correctedBestZ-range; i <= correctedBestZ+range; i++) {
                                final int currI = i;
                                mapByZ.get(i).forEach((f)->{
                                    //
                                    renamingTasks.add(()-> {
                                                int newZ = currI-zOffset;

                                                int oldZ = currI;
                                                if(oldZ<1)oldZ=1;
                                                if(oldZ>maxZ) oldZ = maxZ;

                                                Path newFilePath = destTileFolder.resolve(f.getFileName().toString().replace(String.format("_z%03d",oldZ),String.format("_z%03d",newZ)).replace(oldRegSubstring,newRegSubstring));
                                                if(Files.exists(newFilePath)){
                                                    //System.out.println("skipping file that already exists:" + newFilePath);
                                                    return;
                                                }

                                                try{
                                                    Files.copy(f, newFilePath);
                                                    //System.out.println("file copied:" + f + " to " + newFilePath);
                                                }catch (Exception e){
                                                    do{
                                                        try{
                                                            Thread.sleep(1000);
                                                            Files.copy(f, newFilePath);
                                                            System.out.println("file copied:" + f + " to " + newFilePath);
                                                        }catch (Exception e2){
                                                            e.printStackTrace();
                                                        }
                                                    }while (!Files.exists(newFilePath));
                                                }
                                            }
                                    );
                                });
                        }

                        renamingTasks.forEach(t->es_copy.submit(t));
                    }
                }



                es_copy.shutdown();
                try{
                    es_copy.awaitTermination(1, TimeUnit.DAYS);
                }catch ( Exception e){
                    e.printStackTrace();
                }

            }else{
                System.err.println("bestFocus tiff not found for the given folder:"+tileFolder.getFileName().toString());
            }
        } catch (IOException e){
            e.printStackTrace();
        }
    }

}
