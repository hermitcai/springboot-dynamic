package com.rdpass.dynamic.demo.controller;

import com.rdpaas.dynamic.core.ModuleApplication;
import com.rdpass.dynamic.demo.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.*;

import java.net.URL;

@Api(value = "UserController", tags = "用户管理Api")
@RestController
@RequestMapping("/user")
public class UserController implements ApplicationContextAware {

    @Autowired
    private UserService userService;

    @Autowired
    private ModuleApplication moduleApplication;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @ApiOperation(nickname = "get", value = "根据ID获取用户")
    @GetMapping("get")
    public String get(@RequestParam Long id){
        return userService.get(id).toString();
    }

    @ApiOperation(nickname = "query", value = "查询用户信息")
    @GetMapping("query")
    public String query(@RequestParam Long id){
        return userService.get(id).toString();
    }

    @ApiOperation(nickname = "loader", value = "loader")
    @GetMapping("loader")
    public String loader(@RequestParam Long id){
        return userService.get(id).toString();
    }

    @ApiOperation(nickname = "loader1", value = "loader1")
    @GetMapping("loader1")
    public String loader1(@RequestParam Long id){
        System.out.println("loader1 = " + id);
        return id.toString();
    }

    @ApiOperation(nickname = "loader2", value = "loader2")
    @GetMapping("loader2")
    public String loader2(@RequestParam Long id){
        System.out.println("loader2 = " + id);
        return id.toString();
    }

    @ApiOperation(nickname = "reload", value = "重新加载")
    @GetMapping("reload")
    public void reload() throws Exception {
        URL url = new URL("file:/Users/hermit/IdeaProjects/spring-boot/dynamic/springboot-dynamic/springboot-dynamic-demo-ext/target/springboot-dynamic-demo-ext-1.0.0-BASE-SNAPSHOT.jar");
        moduleApplication.reloadJar(url, applicationContext, sqlSessionFactory);
    }

}
