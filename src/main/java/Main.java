import javax.swing.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {



    public static void main(String [] args) throws Exception{

        String sourceDir = args[0];

        Path destDir =  Paths.get(args[1]);

        int keepZSlizes = Integer.parseInt(args[2]);

        final int mt = (args.length>3)?Integer.parseInt(args[3]):4;

        System.out.println("sourceDir = " + sourceDir);

        System.out.println("destDir = " + sourceDir);

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
        BufferedWriter bw = new BufferedWriter(new FileWriter(mapFile));

        bw.write("New Region Index\tOld Experiment Folder\tOld Region Index\tWidth_in_Tiles\tHeight_in_Tiles\tTile folder paths");
        AtomicInteger newRegIdx = new AtomicInteger(0);

        expRegTiles.entrySet().stream().sorted(Comparator.comparing(o->o.getKey())).forEach(exp->{
            System.out.println(exp.getKey());
            exp.getValue().entrySet().stream().sorted(Comparator.comparing(o->o.getKey())).forEach(region->{
                Collections.sort(region.getValue());
                final int newRegIDX = newRegIdx.incrementAndGet();

                List<Path> trunkRegPaths = region.getValue().stream().map(s->s.subpath(s.getNameCount()-3, s.getNameCount())).collect(Collectors.toList());

                int maxYValue = trunkRegPaths.stream().map(s->s.getFileName().toString()).map(s->s.substring(s.lastIndexOf("_Y")+2,s.lastIndexOf("_Y")+4)).map(s->Integer.parseInt(s)).max(Comparator.naturalOrder()).get();

                int maxXValue = trunkRegPaths.stream().map(s->s.getFileName().toString()).map(s->s.substring(s.lastIndexOf("_X")+2,s.lastIndexOf("_X")+4)).map(s->Integer.parseInt(s)).max(Comparator.naturalOrder()).get();

                String out = "\t"+ newRegIDX +"\t" + exp.getKey().getFileName() + "\t" + region.getKey() + "\t" + maxXValue  + "\t" + maxYValue + "\t" + trunkRegPaths;
                System.out.println(out);
                try{
                    bw.write(out);
                    bw.newLine();
                }catch (IOException ex){
                    ex.printStackTrace();
                }

                region.getValue().forEach(tileFolder->{
                    String oldRegSubstring = String.format("reg%03d",region.getKey());
                    String newRegSubstring  = String.format("reg%03d",newRegIDX);
                    Path newTileDir = destDir.resolve(tileFolder.subpath(tileFolder.getNameCount()-3, tileFolder.getNameCount())).resolveSibling(tileFolder.getFileName().toString().replace(oldRegSubstring,newRegSubstring));
                    try{
                        Files.createDirectories(newTileDir);
                        System.out.println("Dir created:" + newTileDir);
                    }catch (Exception ex){
                        ex.printStackTrace();
                    }

                    System.out.println("Working on a dir: "+ tileFolder.toString());
                    Path bestFocusTemp = tileFolder.getParent().resolveSibling("bestFocus");

                    if(!Files.exists(bestFocusTemp)) {
                        bestFocusTemp = tileFolder.getParent().resolve("bestFocus");
                    }

                    final Path bestFocus = bestFocusTemp;

                    copyZStackSubset(tileFolder, newTileDir, keepZSlizes, bestFocus, mt, oldRegSubstring, newRegSubstring);

                });


            });
        });

        System.exit(0);

    }

    private static void copyZStackSubset(Path tileFolder, Path destTileFolder, int keepZSlizes, Path bestFocusFolder, int numThreads, String oldRegSubstring, String newRegSubstring){

        try{

            ExecutorService es_copy = Executors.newFixedThreadPool(numThreads);

            Optional<Path>  o = Files.find(bestFocusFolder,1,(filePath, fileAttr) ->  filePath.getFileName().toString().startsWith(tileFolder.toFile().getName())).findFirst();
            if(o.isPresent()){
                System.out.println("bestFocus tiff found:"+ o.get().getFileName().toString());

                int bestZ = Integer.parseInt(o.get().getFileName().toString().split("_Z")[1].substring(0,2));

                System.out.println("bestZ="+bestZ);

                Map<Integer, List<Path>> mapByZ = Files.find(tileFolder, 1, (filePath, fileAttr) -> filePath.getFileName().toString().endsWith(".tif")).collect(Collectors.groupingBy(
                                f->Integer.parseInt(f.getFileName().toString().split("_z")[1].substring(0,3))
                ));

                int maxZ = mapByZ.keySet().stream().max(Comparator.naturalOrder()).get();
                System.out.println("maxZ = " + maxZ);

                int range = keepZSlizes/2;

                if(bestZ-range<1){
                    bestZ=range+1;
                }
                if(bestZ+range>maxZ){
                    bestZ = maxZ-range;
                }

                int zOffset = (bestZ-range)-1;

                List<Runnable> deletionTasks = new ArrayList<>();
                List<Runnable> renamingTasks = new ArrayList<>();

                for (int i = 1; i <= maxZ; i++) {
                    final int currI = i;
                    if(Math.abs(i-bestZ)<=range){
                        mapByZ.get(i).forEach((f)->{
                            //
                            renamingTasks.add(()-> {
                                        Path newFilePath = destTileFolder.resolve(f.getFileName().toString().replace(String.format("_z%03d",currI),String.format("_z%03d",currI-zOffset)).replace(oldRegSubstring,newRegSubstring));
                                        if(Files.exists(newFilePath)) return;

                                        try{
                                            Files.copy(f, newFilePath);
                                            System.out.println("file copied:" + f + " to " + newFilePath);
                                        }catch (Exception e){
                                            try{
                                                Files.copy(f, newFilePath);
                                                System.out.println("file copied:" + f + " to " + newFilePath);
                                            }catch (Exception e2){
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                            );
                        });
                    }
                }

                renamingTasks.forEach(t->es_copy.submit(t));
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
