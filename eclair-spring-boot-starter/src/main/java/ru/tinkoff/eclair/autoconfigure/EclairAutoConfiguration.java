/*
 * Copyright 2018 Tinkoff Bank
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.tinkoff.eclair.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import ru.tinkoff.eclair.aop.EclairProxyCreator;
import ru.tinkoff.eclair.core.AnnotationDefinitionFactory;
import ru.tinkoff.eclair.core.BeanFactoryHelper;
import ru.tinkoff.eclair.core.ExpressionEvaluator;
import ru.tinkoff.eclair.logger.EclairLogger;
import ru.tinkoff.eclair.logger.SimpleLogger;
import ru.tinkoff.eclair.logger.collector.*;
import ru.tinkoff.eclair.logger.facade.Slf4JLoggerFacadeFactory;
import ru.tinkoff.eclair.printer.*;
import ru.tinkoff.eclair.printer.processor.JaxbElementWrapper;
import ru.tinkoff.eclair.printer.resolver.AliasedPrinterResolver;
import ru.tinkoff.eclair.printer.resolver.PrinterResolver;

import java.util.List;

import static java.util.Collections.singletonList;
import static java.util.Objects.isNull;

/**
 * @author Vyacheslav Klapatnyuk
 */
@Configuration
@EnableConfigurationProperties(EclairProperties.class)
public class EclairAutoConfiguration {

    private final GenericApplicationContext applicationContext;

    public EclairAutoConfiguration(GenericApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    @ConditionalOnMissingBean
    public LogInCollectorFactory<?> stringJoinerLogInCollectorFactory() {
        return StringJoinerLogInCollectorFactory.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean
    public LogOutCollector<?> toStringLogOutCollector() {
        return ToStringLogOutCollector.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean
    public EclairLogger simpleLogger(LogInCollectorFactory<?> logInCollectorFactory, LogOutCollector<?> logOutCollector) {
        return new SimpleLogger(
                new Slf4JLoggerFacadeFactory(),
                LoggingSystem.get(SimpleLogger.class.getClassLoader()),
                logInCollectorFactory,
                logOutCollector
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ExpressionEvaluator expressionEvaluator() {
        ExpressionParser expressionParser = new SpelExpressionParser(new SpelParserConfiguration(SpelCompilerMode.MIXED, null));
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext();
        evaluationContext.setBeanResolver(new BeanFactoryResolver(applicationContext));
        return new ExpressionEvaluator(expressionParser, evaluationContext);
    }

    @Bean
    public EclairProxyCreator eclairProxyCreator(PrinterResolver printerResolver,
                                                 List<EclairLogger> orderedLoggers,
                                                 EclairProperties eclairProperties,
                                                 ExpressionEvaluator expressionEvaluator) {
        AnnotationDefinitionFactory annotationDefinitionFactory = new AnnotationDefinitionFactory(printerResolver);
        EclairProxyCreator eclairProxyCreator =
                new EclairProxyCreator(applicationContext, annotationDefinitionFactory, orderedLoggers, expressionEvaluator, printerResolver);
        eclairProxyCreator.setOrder(Ordered.HIGHEST_PRECEDENCE);
        eclairProxyCreator.setFrozen(false);
        eclairProxyCreator.setValidate(eclairProperties.isValidate());
        return eclairProxyCreator;
    }

    @Configuration
    static class PrinterConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @Order(0)
        public OverriddenToStringPrinter overriddenToStringPrinter() {
            return new OverriddenToStringPrinter();
        }

        @Configuration
        @ConditionalOnClass(Jaxb2Marshaller.class)
        static class Jaxb2PrinterConfiguration {

            @Bean
            @ConditionalOnSingleCandidate(Jaxb2Marshaller.class)
            @ConditionalOnMissingBean(Jaxb2Printer.class)
            @Order(100)
            public Printer jaxb2Printer(ObjectProvider<Jaxb2Marshaller> jaxb2Marshaller) {
                Jaxb2Marshaller marshaller = jaxb2Marshaller.getObject();
                return new Jaxb2Printer(marshaller)
                        .addPreProcessor(new JaxbElementWrapper(marshaller));
            }
        }

        @Configuration
        @ConditionalOnClass(ObjectMapper.class)
        static class JacksonPrinterConfiguration {

            @Bean
            @ConditionalOnSingleCandidate(ObjectMapper.class)
            @ConditionalOnMissingBean
            @Order(200)
            public JacksonPrinter jacksonPrinter(ObjectProvider<ObjectMapper> objectMapper) {
                return new JacksonPrinter(objectMapper.getObject());
            }
        }

        @Bean
        @ConditionalOnMissingBean(ignored = OverriddenToStringPrinter.class)
        @Order(300)
        public ToStringPrinter toStringPrinter() {
            return new ToStringPrinter();
        }

        @Bean
        @ConditionalOnMissingBean
        public PrinterResolver printerResolver(GenericApplicationContext applicationContext,
                                               ObjectProvider<List<Printer>> printersObjectProvider) {
            List<Printer> orderedPrinters = printersObjectProvider.getIfAvailable();
            List<Printer> printers = isNull(orderedPrinters) ? singletonList(PrinterResolver.defaultPrinter) : orderedPrinters;
            BeanFactoryHelper beanFactoryHelper = BeanFactoryHelper.getInstance();
            return new AliasedPrinterResolver(
                    beanFactoryHelper.collectToOrderedMap(applicationContext, Printer.class, printers),
                    beanFactoryHelper.getAliases(applicationContext, Printer.class));
        }
    }
}
