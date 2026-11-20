// 生成物: YDE 本体の settings・buildSrc・版カタログには依存しない。
plugins {{
    java
    id("org.springframework.boot") version "{config.springBootVersion}"
}}

group = "{#each config.package}{.}{#if more}.{/if}{/each}"
version = "{config.version}"

java {{
    toolchain {{ languageVersion = JavaLanguageVersion.of({config.javaVersion}) }}
}}

repositories {{ mavenCentral() }}

dependencies {{
    implementation(platform("org.springframework.boot:spring-boot-dependencies:{config.springBootVersion}"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:{config.mybatisVersion}")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}}

tasks.withType<JavaCompile>().configureEach {{
    options.encoding = "UTF-8"
    options.release = {config.javaVersion}
    options.compilerArgs.add("-parameters")
}}

tasks.withType<Test>().configureEach {{ useJUnitPlatform() }}

tasks.bootJar {{ archiveFileName = "{config.name}.jar" }}
