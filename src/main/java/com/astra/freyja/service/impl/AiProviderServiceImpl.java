package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.AiProviderDTO;
import com.astra.freyja.dto.AiProviderQuery;
import com.astra.freyja.dto.AiProviderVO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiProviderService;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 提供商管理实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderServiceImpl implements AiProviderService {

    private static final String PROVIDER_CACHE_KEY = "freyja:ai:provider:enabled";

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final CryptoUtil cryptoUtil;
    private final AiModelFactory aiModelFactory;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Page<AiProviderVO> page(AiProviderQuery query) {
        LambdaQueryWrapper<AiProvider> wrapper = new LambdaQueryWrapper<AiProvider>()
                .like(StringUtils.isNotBlank(query.getProviderName()), AiProvider::getProviderName, query.getProviderName())
                .eq(StringUtils.isNotBlank(query.getProviderType()), AiProvider::getProviderType, query.getProviderType())
                .eq(query.getStatus() != null, AiProvider::getStatus, query.getStatus())
                .orderByDesc(AiProvider::getCreateTime);
        Page<AiProvider> page = providerMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        Page<AiProviderVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Override
    public AiProviderVO getById(Long id) {
        return toVO(getEntity(id));
    }

    @Override
    public List<AiProviderVO> listEnabled() {
        Object cached = redisTemplate.opsForValue().get(PROVIDER_CACHE_KEY);
        if (cached instanceof List<?>) {
            return castList(cached).stream().map(this::toVO).toList();
        }
        List<AiProvider> list = providerMapper.selectList(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getStatus, 1)
                .orderByDesc(AiProvider::getCreateTime));
        redisTemplate.opsForValue().set(PROVIDER_CACHE_KEY, list);
        return list.stream().map(this::toVO).toList();
    }

    @Override
    public void create(AiProviderDTO dto) {
        validate(dto);
        if (AiModelFactory.TYPE_OPENAI.equals(dto.getProviderType()) && StringUtils.isBlank(dto.getApiKey())) {
            throw new BizException("API Key 不能为空");
        }
        Long count = providerMapper.selectCount(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getProviderCode, dto.getProviderCode()));
        if (count != null && count > 0) {
            throw new BizException("提供商编码已存在");
        }
        AiProvider entity = new AiProvider();
        entity.setProviderCode(dto.getProviderCode());
        entity.setProviderName(dto.getProviderName());
        entity.setProviderType(dto.getProviderType());
        entity.setApiKey(cryptoUtil.encrypt(dto.getApiKey()));
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setTimeout(dto.getTimeout() == null ? 30 : dto.getTimeout());
        entity.setMaxRetries(dto.getMaxRetries() == null ? 3 : dto.getMaxRetries());
        entity.setEnableBreaker(dto.getEnableBreaker() == null ? 1 : dto.getEnableBreaker());
        entity.setBreakerThreshold(dto.getBreakerThreshold() == null ? 10 : dto.getBreakerThreshold());
        entity.setBreakerTimeout(dto.getBreakerTimeout() == null ? 30 : dto.getBreakerTimeout());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        providerMapper.insert(entity);
        evictCache();
    }

    @Override
    public void update(AiProviderDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("主键不能为空");
        }
        validate(dto);
        AiProvider existing = getEntity(dto.getId());
        if (!existing.getProviderCode().equals(dto.getProviderCode())) {
            Long count = providerMapper.selectCount(new LambdaQueryWrapper<AiProvider>()
                    .eq(AiProvider::getProviderCode, dto.getProviderCode())
                    .ne(AiProvider::getId, dto.getId()));
            if (count != null && count > 0) {
                throw new BizException("提供商编码已存在");
            }
        }
        existing.setProviderCode(dto.getProviderCode());
        existing.setProviderName(dto.getProviderName());
        existing.setProviderType(dto.getProviderType());
        if (StringUtils.isNotBlank(dto.getApiKey())) {
            existing.setApiKey(cryptoUtil.encrypt(dto.getApiKey()));
        }
        existing.setBaseUrl(dto.getBaseUrl());
        existing.setTimeout(dto.getTimeout() == null ? 30 : dto.getTimeout());
        existing.setMaxRetries(dto.getMaxRetries() == null ? 3 : dto.getMaxRetries());
        existing.setEnableBreaker(dto.getEnableBreaker() == null ? 1 : dto.getEnableBreaker());
        existing.setBreakerThreshold(dto.getBreakerThreshold() == null ? 10 : dto.getBreakerThreshold());
        existing.setBreakerTimeout(dto.getBreakerTimeout() == null ? 30 : dto.getBreakerTimeout());
        existing.setStatus(dto.getStatus());
        existing.setRemark(dto.getRemark());
        providerMapper.updateById(existing);
        evictCache();
        aiModelFactory.evict(existing.getId());
    }

    @Override
    public void delete(Long id) {
        getEntity(id);
        providerMapper.deleteById(id);
        evictCache();
        aiModelFactory.evict(id);
    }

    @Override
    public void test(Long providerId, String modelCode) {
        AiProvider provider = getEntity(providerId);

        if (StringUtils.isBlank(modelCode)) {
            List<AiModel> models = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getProviderId, providerId)
                    .eq(AiModel::getStatus, 1)
                    .orderByAsc(AiModel::getSortOrder));
            if (models == null || models.isEmpty()) {
                throw new BizException("该提供商下无启用中的模型");
            }
            modelCode = models.get(0).getModelCode();
        }
        try {
            ChatModel chatModel = aiModelFactory.getChatModel(providerId, modelCode);
            String response = chatModel.call("ping");
            log.info("AI 提供商测试调用成功, providerId={}, modelCode={}, response={}", providerId, modelCode, response);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 提供商测试调用失败, providerId={}, modelCode={}", providerId, modelCode, e);
            throw new BizException("连通性测试失败：" + e.getMessage());
        }
    }

    private AiProvider getEntity(Long id) {
        AiProvider entity = providerMapper.selectById(id);
        if (entity == null) {
            throw new BizException("AI 提供商不存在");
        }
        return entity;
    }

    private void validate(AiProviderDTO dto) {
        if (StringUtils.isBlank(dto.getProviderName())) {
            throw new BizException("提供商名称不能为空");
        }
        if (StringUtils.isBlank(dto.getProviderType())) {
            throw new BizException("接入类型不能为空");
        }
        if (!AiModelFactory.TYPE_OPENAI.equals(dto.getProviderType())
                && !AiModelFactory.TYPE_OLLAMA.equals(dto.getProviderType())) {
            throw new BizException("不支持的接入类型，仅支持 " + AiModelFactory.TYPE_OPENAI + "/" + AiModelFactory.TYPE_OLLAMA);
        }
        if (dto.getStatus() == null) {
            throw new BizException("状态不能为空");
        }
    }

    private void evictCache() {
        redisTemplate.delete(PROVIDER_CACHE_KEY);
    }

    private AiProviderVO toVO(AiProvider entity) {
        AiProviderVO vo = new AiProviderVO();
        vo.setId(entity.getId());
        vo.setProviderCode(entity.getProviderCode());
        vo.setProviderName(entity.getProviderName());
        vo.setProviderType(entity.getProviderType());
        String rawKey = null;
        try {
            rawKey = cryptoUtil.decrypt(entity.getApiKey());
        } catch (Exception e) {
            log.warn("AI 提供商 [id={}, code={}] API Key 解密失败: {}",
                    entity.getId(), entity.getProviderCode(), e.getMessage());
        }
        vo.setMaskedApiKey(StringUtils.isNotBlank(rawKey) ? cryptoUtil.mask(rawKey) : (StringUtils.isNotBlank(entity.getApiKey()) ? "[密钥失效/需重填]" : ""));
        vo.setHasApiKey(StringUtils.isNotBlank(rawKey));
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setTimeout(entity.getTimeout());
        vo.setMaxRetries(entity.getMaxRetries());
        vo.setEnableBreaker(entity.getEnableBreaker());
        vo.setBreakerThreshold(entity.getBreakerThreshold());
        vo.setBreakerTimeout(entity.getBreakerTimeout());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private List<AiProvider> castList(Object cached) {
        return (List<AiProvider>) cached;
    }
}