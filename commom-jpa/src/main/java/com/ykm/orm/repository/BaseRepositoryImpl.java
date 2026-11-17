//package com.ykm.orm.repository;
//
//import jakarta.persistence.EntityManager;
//import org.hibernate.Session;
//import org.hibernate.engine.jdbc.spi.JdbcServices;
//import org.hibernate.engine.spi.SessionFactoryImplementor;
//import org.hibernate.internal.AbstractSharedSessionContract;
//import org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation;
//import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.function.Supplier;
//
//public class BaseRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> implements BaseRepository<T, ID> {
//
//    private final EntityManager em;
//
//    // REQUIRED constructor for Spring Data JPA 3.x
//    public BaseRepositoryImpl(JpaMetamodelEntityInformation<T, ID> entityInformation, EntityManager em) {
//        super(entityInformation, em);
//        this.em = em;
//    }
//
//    // Optional: keep the domainClass-based constructor if you use it anywhere
//    public BaseRepositoryImpl(Class<T> domainClass, EntityManager em) {
//        super(domainClass, em);
//        this.em = em;
//    }
//
//    @Override
//    public <S extends T> S save(S entity) {
//        return unsupported();
//    }
//
//    @Override
//    public <S extends T> List<S> saveAll(Iterable<S> entities) {
//        return unsupported();
//    }
//
//    @Override
//    public <S extends T> S saveAndFlush(S entity) {
//        return unsupported();
//    }
//
//    @Override
//    public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {
//        return unsupported();
//    }
//    @Transactional
//    public <S extends T> S persist(S entity) {
//        em.persist(entity);
//        return entity;
//    }
//    @Transactional
//    public <S extends T> S persistAndFlush(S entity) {
//        persist(entity);
//        em.flush();
//        return entity;
//    }
//    @Transactional
//    public <S extends T> List<S> persistAll(Iterable<S> entities) {
//        List<S> result = new ArrayList<>();
//        for(S entity : entities) {
//            result.add(persist(entity));
//        }
//        return result;
//    }
//
//    @Transactional
//    public <S extends T> List<S> peristAllAndFlush(Iterable<S> entities) {
//        return executeBatch(() -> {
//            List<S> result = new ArrayList<>();
//            for(S entity : entities) {
//                result.add(persist(entity));
//            }
//            em.flush();
//            return result;
//        });
//    }
//    @Transactional
//    public <S extends T> S merge(S entity) {
//        return em.merge(entity);
//    }
//    @Transactional
//    @Override
//    public <S extends T> S mergeAndFlush(S entity) {
//        S result = merge(entity);
//        em.flush();
//        return result;
//    }
//    @Transactional
//    @Override
//    public <S extends T> List<S> mergeAll(Iterable<S> entities) {
//        List<S> result = new ArrayList<>();
//        for(S entity : entities) {
//            result.add(merge(entity));
//        }
//        return result;
//    }
//    @Transactional
//    @Override
//    public <S extends T> List<S> mergeAllAndFlush(Iterable<S> entities) {
//        return executeBatch(() -> {
//            List<S> result = new ArrayList<>();
//            for(S entity : entities) {
//                result.add(merge(entity));
//            }
//            em.flush();
//            return result;
//        });
//    }
//    @Transactional
//    public <S extends T> S update(S entity) {
//        session().update(entity);
//        return entity;
//    }
//    @Transactional
//    @Override
//    public <S extends T> S updateAndFlush(S entity) {
//        update(entity);
//        em.flush();
//        return entity;
//    }
//    @Transactional
//    @Override
//    public <S extends T> List<S> updateAll(Iterable<S> entities) {
//        List<S> result = new ArrayList<>();
//        for(S entity : entities) {
//            result.add(update(entity));
//        }
//        return result;
//    }
//    @Transactional
//    @Override
//    public <S extends T> List<S> updateAllAndFlush(Iterable<S> entities) {
//        return executeBatch(() -> {
//            List<S> result = new ArrayList<>();
//            for(S entity : entities) {
//                result.add(update(entity));
//            }
//            em.flush();
//            return result;
//        });
//    }
//    @Transactional
//    protected Integer getBatchSize(Session session) {
//        SessionFactoryImplementor sessionFactory = session
//                .getSessionFactory()
//                .unwrap(SessionFactoryImplementor.class);
//
//        final JdbcServices jdbcServices = sessionFactory
//                .getServiceRegistry()
//                .getService(JdbcServices.class);
//
//        if(!jdbcServices.getExtractedMetaDataSupport().supportsBatchUpdates()) {
//            return Integer.MIN_VALUE;
//        }
//        return session.unwrap(AbstractSharedSessionContract.class)
//                .getConfiguredJdbcBatchSize();
//    }
//    @Transactional
//    protected <R> R executeBatch(Supplier<R> callback) {
//        Session session = session();
//        Integer jdbcBatchSize = getBatchSize(session);
//        try {
//            if (jdbcBatchSize == null) {
//                session.setJdbcBatchSize(10);
//            }
//            return callback.get();
//        } finally {
//            session.setJdbcBatchSize(null);
//        }
//    }
//
//    protected Session session() {
//        return em.unwrap(Session.class);
//    }
//
//    protected <S extends T> S unsupported() {
//        throw new UnsupportedOperationException(
//                "There's no such thing as a save method in JPA, so don't use this hack!"
//        );
//    }
//}