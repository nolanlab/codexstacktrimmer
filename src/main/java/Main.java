import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Main {



    public static void main(String [] args) throws Exception{

        int keepZSlizes = Integer.parseInt(args[1]);

        if(keepZSlizes%2==0){
            throw new IllegalArgumentException("Number of Z-slices to keep must be odd");
        }

        Files.find(Paths.get(args[0]),
                Integer.MAX_VALUE,
                (filePath, fileAttr) -> filePath.toFile().getName().equals("tiles") && filePath.toFile().isDirectory())
                .forEach(p->{
                    System.out.println("Working on a dir: "+ p.toString());
                    Path bestFocus = p.resolveSibling("bestFocus");
                    if(Files.exists(bestFocus)){
                        try{
                            Files.find(p,
                                    1,
                                    (filePath, fileAttr) -> filePath.getFileName().toString().matches("reg[0-9][0-9][0-9]_X[0-9][0-9]_Y[0-9][0-9]") && filePath.toFile().isDirectory()).forEach(
                                    tileFolder->{
                                        try{
                                            Optional<Path>  o = Files.find(bestFocus,1,(filePath, fileAttr) ->  filePath.getFileName().toString().startsWith(tileFolder.toFile().getName())).findFirst();
                                            if(o.isPresent()){
                                                System.out.println("bestFocus tiff found:"+ o.get().getFileName().toString());

                                                int bestZ = Integer.parseInt(o.get().getFileName().toString().split("_Z")[1].substring(0,2));

                                                System.out.println("bestZ="+bestZ);

                                                Map<Integer, List<Path>> mapByZ=
                                                        Files.find(tileFolder, 1, (filePath, fileAttr) -> filePath.getFileName().toString().endsWith(".tif")).collect(Collectors.groupingBy(
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



                                                for (int i = 1; i <= maxZ; i++) {
                                                    final int currI = i;
                                                    if(Math.abs(i-bestZ)<=range){
                                                        mapByZ.get(i).forEach((f)->{
                                                            try{
                                                                System.out.println("renaming file:" + f.getFileName().toString() + " to " + f.resolveSibling(f.getFileName().toString().replace(String.format("_z%03d",currI),String.format("_z%03d",currI-zOffset))).getFileName().toString());
                                                                //Files.move(f, f.resolveSibling(f.getFileName().toString().replace(String.format("_z%03d",currI),String.format("_z%03d",currI))));

                                                            }catch (Exception e){
                                                                e.printStackTrace();
                                                            }
                                                        });

                                                    }else{
                                                        //Delete slices that are outside of range

                                                        mapByZ.get(i).forEach((f)->{
                                                            try{
                                                                System.out.println("deleting file:" +f.getFileName().toString());
                                                                //Files.delete(f);
                                                            }catch (Exception e){
                                                                e.printStackTrace();
                                                            }
                                                        });
                                                    }
                                                }

                                            }else{
                                                System.err.println("bestFocus tiff not found for the given folder:"+tileFolder.getFileName().toString());
                                            }
                                        } catch (IOException e){
                                            e.printStackTrace();
                                        }
                                        System.exit(0);
                                    }
                            );
                        }catch (IOException e){
                            e.printStackTrace();
                        }
                    }else {
                        System.out.println("bestFocus folder not found. Continuing");
                    }
                });


    }

}
