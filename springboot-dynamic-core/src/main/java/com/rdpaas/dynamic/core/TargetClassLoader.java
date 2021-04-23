package com.rdpaas.dynamic.core;


import javafx.application.Application;
import org.apache.commons.lang3.StringUtils;
import org.omg.CORBA.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.jar.JarFile;


/**
 * 动态加载外部jar包的自定义类加载器
 * @author rongdi
 * @date 2021-03-06
 * @blog https://www.cnblogs.com/rongdi
 */
public class TargetClassLoader extends URLClassLoader {

    private Logger logger = LoggerFactory.getLogger(TargetClassLoader.class);

    private final static String CLASS_SUFFIX = ".class";

    private final static String XML_SUFFIX = ".xml";

    private final static String MAPPER_SUFFIX = "mapper/";

    //属于本类加载器加载的jar包
    private JarFile jarFile;

    private File rootFileFolder;

    Map<String, Long> fileModifyMap = new HashMap<>();

    private Map<String, byte[]> classBytesMap = new HashMap<>();

    private Map<String, Class<?>> classesMap = new HashMap<>();

    private Map<String, byte[]> xmlBytesMap = new HashMap<>();

    public TargetClassLoader(ClassLoader classLoader, URL... urls) {
        super(urls, classLoader);
        URL url = urls[0];
        String path = url.getPath();
        rootFileFolder = new File(path);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] buf = classBytesMap.get(name);
        if (buf == null) {
            return super.findClass(name);
        }
        if(classesMap.containsKey(name)) {
            return classesMap.get(name);
        }
        /**
         * 这里应该算是骚操作了，我不知道市面上有没有人这么做过，反正我是想了好久，遇到各种因为spring要生成代理对象
         * 在他自己的AppClassLoader找不到原对象导致的报错，注意如果你限制你的扩展包你不会有AOP触碰到的类或者@Transactional这种
         * 会产生代理的类，那么其实你不用这么骚，直接在这里调用defineClass把字节码装载进去就行了，不会有什么问题，最多也就是
         * 在加载mybatis的xml那里前后加三句话，
         * 1、获取并使用一个变量保存当前线程类加载器
         * 2、将自定义类加载器设置到当前线程类加载器
         * 3、还原当前线程类加载器为第一步保存的类加载器
         * 这样之后mybatis那些xml里resultType，resultMap之类的需要访问扩展包的Class的就不会报错了。
         * 不过直接用现在这种骚操作，更加一劳永逸，不会有mybatis的问题了
         */
        return loadClass(name,buf);
    }

    /**
     * 使用反射强行将类装载的归属给当前类加载器的父类加载器也就是AppClassLoader，如果报ClassNotFoundException
     * 则递归装载
     * @param name
     * @param bytes
     * @return
     */
    private Class<?> loadClass(String name, byte[] bytes) throws ClassNotFoundException {

        Object[] args = new Object[]{name, bytes, 0, bytes.length};
        try {
            /**
             * 拿到当前类加载器的parent加载器AppClassLoader
             */
            ClassLoader parent = this.getParent();
            /**
             * 首先要明确反射是万能的，仿造org.springframework.cglib.core.ReflectUtils的写法，强行获取被保护
             * 的方法defineClass的对象，然后调用指定类加载器的加载字节码方法，强行将加载归属塞给它，避免被spring的AOP或者@Transactional
             * 触碰到的类需要生成代理对象，而在AppClassLoader下加载不到外部的扩展类而报错，所以这里强行将加载外部扩展包的类的归属给
             * AppClassLoader，让spring的cglib生成代理对象时可以加载到原对象
             */
            Method classLoaderDefineClass = (Method) AccessController.doPrivileged(new PrivilegedExceptionAction() {
                @Override
                public Object run() throws Exception {
                    return ClassLoader.class.getDeclaredMethod("defineClass",
                            String.class, byte[].class, Integer.TYPE, Integer.TYPE);
                }
            });
            if(!classLoaderDefineClass.isAccessible()) {
                classLoaderDefineClass.setAccessible(true);
            }
            return (Class<?>)classLoaderDefineClass.invoke(parent,args);
        } catch (Exception e) {
            if(e instanceof InvocationTargetException) {
                String message = ((InvocationTargetException) e).getTargetException().getCause().toString();
                /**
                 * 无奈，明明ClassNotFoundException是个异常，非要抛个InvocationTargetException，导致
                 * 我这里一个不太优雅的判断
                 */
                if(message.startsWith("java.lang.ClassNotFoundException")) {
                    String notClassName = message.split(":")[1];
                    if(StringUtils.isEmpty(notClassName)) {
                        throw new ClassNotFoundException(message);
                    }
                    notClassName = notClassName.trim();
                    byte[] bytes1 = classBytesMap.get(notClassName);
                    if(bytes1 == null) {
                        throw new ClassNotFoundException(message);
                    }
                    /**
                     * 递归装载未找到的类
                     */
                    Class<?> notClass = loadClass(notClassName, bytes1);
                    if(notClass == null) {
                        throw new ClassNotFoundException(message);
                    }
                    classesMap.put(notClassName,notClass);
                    return loadClass(name,bytes);
                }
            } else {
                logger.error("",e);
            }
        }
        return null;
    }

    public Map<String,byte[]> getXmlBytesMap() {
        return xmlBytesMap;
    }


    /**
     * 方法描述 初始化类加载器，保存字节码
     */
    public Map<String, Class> load() throws IOException {

        Map<String, Class> cacheClassMap = new HashMap<>();


        File[] fileFolderList = rootFileFolder.listFiles();
        assert fileFolderList != null;
        List<File> fileList = new ArrayList<>();
        fileList = loadAllFile(fileFolderList, fileList);

        xmlBytesMap = new HashMap<>();
        classBytesMap = new HashMap<>();
        for (File file : fileList) {
            String absolutePath = file.getAbsolutePath();
            String[] classPathList = absolutePath.split("target/classes/");
            String fileName = file.getName();
            String fileApplicationPath = classPathList[1].replace(CLASS_SUFFIX, "").replaceAll("/", ".");
            Long fileModifyTime = fileModifyStaticMap.get(fileApplicationPath);
            fileModifyTime = null == fileModifyTime ? 0L : fileModifyTime;
            if (file.lastModified() > fileModifyTime) {
                if (fileName.endsWith(XML_SUFFIX) && fileApplicationPath.contains("mapper")) {
                    xmlBytesMap.put(fileApplicationPath, Files.readAllBytes(Paths.get(file.getPath())));
                    System.out.println("reload xml file = " + fileApplicationPath);
                }
                else if (fileName.endsWith(CLASS_SUFFIX)) {
                    classBytesMap.put(fileApplicationPath, Files.readAllBytes(Paths.get(file.getPath())));
                    System.out.println("reload class file = " + fileApplicationPath);
                }
            }
            fileModifyStaticMap.put(fileApplicationPath, file.lastModified());
        }

        //将jar中的每一个class字节码进行Class载入
        for (Map.Entry<String, byte[]> entry : classBytesMap.entrySet()) {
            String key = entry.getKey();
            Class<?> aClass = null;
            try {
                aClass = loadClass(key);
            } catch (ClassNotFoundException e) {
                logger.error("",e);
            }
            cacheClassMap.put(key, aClass);
        }
        return cacheClassMap;

    }

    public Map<String, byte[]> getClassBytesMap() {
        return classBytesMap;
    }

    static Map<String, Long> fileModifyStaticMap = new HashMap<>();
    public static void main(String[] args) throws IOException {

        File rootFileFolder = new File(new URL("file:/Users/hermit/IdeaProjects/spring-boot/dynamic/springboot-dynamic/springboot-dynamic-demo/target/classes").getPath());
        File[] fileFolderList = rootFileFolder.listFiles();
        assert fileFolderList != null;
        List<File> fileList = new ArrayList<>();
        fileList = loadAllFile(fileFolderList, fileList);
        Map<String, byte[]> xmlBytesStaticMap = new HashMap<>();
        Map<String, byte[]> classBytesStaticMap = new HashMap<>();
        for (File file : fileList) {
            String absolutePath = file.getAbsolutePath();
            String[] classPathList = absolutePath.split("target/classes/");
            String fileName = file.getName();
            String fileApplicationPath = classPathList[1].replace(CLASS_SUFFIX, "").replaceAll("/", ".");
            Long fileModifyTime = fileModifyStaticMap.get(fileApplicationPath);
            fileModifyTime = null == fileModifyTime ? 0L : fileModifyTime;
            if (file.lastModified() > fileModifyTime) {
                if (fileName.endsWith(XML_SUFFIX) && fileApplicationPath.contains("mapper")) {
                    xmlBytesStaticMap.put(fileApplicationPath, Files.readAllBytes(Paths.get(file.getPath())));
                    System.out.println("reload xml file = " + fileApplicationPath);
                }
                else if (fileName.endsWith(CLASS_SUFFIX)) {
                    classBytesStaticMap.put(fileApplicationPath, Files.readAllBytes(Paths.get(file.getPath())));
                    System.out.println("reload class file = " + fileApplicationPath);
                }
            }
            fileModifyStaticMap.put(fileApplicationPath, file.lastModified());
        }

    }

    static List<File> loadAllFile(File[] fileFolderList, List<File> fileList) {
        for (File file : fileFolderList) {
            if (null != file.listFiles()) {
                loadAllFile(Objects.requireNonNull(file.listFiles()), fileList);
            }
            else {
                fileList.add(file);
            }
        }
        return fileList;
    }

}