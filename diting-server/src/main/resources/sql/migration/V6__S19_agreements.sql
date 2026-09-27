-- =====================================================================
-- 谛听 · S19 用户协议（V6）
-- 背景：用户注册前必须勾选同意 4 份协议文件（软件许可及服务协议、
--       隐私保护指引、个人信息收集与使用授权书、通话录音与语音识别授权协议）。
--       协议正文由 admin 后台富文本维护，带版本号；注册时记录用户同意的版本。
-- 语义：
--   - agreements：协议正文，agreement_key 唯一；admin 每次保存 version +1；
--   - agreement_consents：用户注册时的同意快照（key + version + 时间 + IP），
--     供审计追溯“用户当时同意的是哪一版”；
--   - 注册接口要求 agreed=true 且版本与当前一致，否则 409 请重新确认。
-- 幂等：表已存在时跳过建表；种子行按 key 逐行插入（已存在则跳过）。
-- 回滚：V6__S19_agreements_rollback.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS agreements (
    id            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    agreement_key VARCHAR(64)     NOT NULL COMMENT 'service_terms/privacy_guide/personal_info_auth/recording_auth',
    title         VARCHAR(128)    NOT NULL COMMENT '协议标题',
    content_html  MEDIUMTEXT      NOT NULL COMMENT '协议正文（富文本 HTML，由 admin 后台维护）',
    version       INT             NOT NULL DEFAULT 1 COMMENT '每次保存 +1；注册时快照此版本',
    sort_order    INT             NOT NULL DEFAULT 0 COMMENT '展示顺序',
    created_at    DATETIME(3)     NOT NULL,
    updated_at    DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agreement_key (agreement_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户协议正文（版本化）';

CREATE TABLE IF NOT EXISTS agreement_consents (
    id            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id       BIGINT          NOT NULL COMMENT 'users.id',
    agreement_key VARCHAR(64)     NOT NULL COMMENT '当时同意的协议 key',
    version       INT             NOT NULL COMMENT '当时同意的协议版本',
    agreed_at     DATETIME(3)     NOT NULL COMMENT '同意时间',
    ip            VARCHAR(64)     NULL COMMENT '注册时客户端 IP',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_agreement (user_id, agreement_key),
    KEY idx_consent_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户注册协议同意快照（审计用）';

-- 种子：4 份 V1.0 协议正文（内容见 /tmp/agreements/seed_rows.txt 生成，key 已存在则跳过）。
INSERT IGNORE INTO agreements
    (agreement_key, title, content_html, version, sort_order, created_at, updated_at)
VALUES
('service_terms','软件许可及服务协议','<p><strong>软件许可及服务协议</strong></p>
<blockquote>
<p><strong>版本编号</strong>：V1.0</p>
<p><strong>更新日期：2026年9月16日</strong></p>
<p><strong>生效日期：2026年9月16日</strong></p>
<p><strong>文件性质</strong>：用户注册强制勾选文件</p>
</blockquote>
<p><strong>前言</strong></p>
<p><strong>【特别提示】</strong>
请您仔细阅读本协议全部条款，特别是以<strong>加粗</strong>、下划线或颜色突出显示的条款。如您对条款内容存有疑问，可通过本平台公示的客服渠道咨询。在您点击确认或以其他方式表示接受本协议前，请务必充分理解各条款内容。</p>
<p><strong>【核心定位声明】</strong>
本平台（"还了吗"）<strong>仅为债务人提供技术识别工具</strong>，包括对用户主动上传的话录音进行语音识别与规则比对，并生成技术分析报告。<strong>本平台不发起、不代理、不组织、不参与任何形式的投诉、举报、协商、谈判、代理维权或债务处置行为</strong>。用户基于本平台出具的技术分析报告所作出的任何决定及由此产生的一切法律后果，由用户本人独立承担。</p>
<p><strong>第一条 定义与解释</strong></p>
<p><strong>1.1 定义</strong></p>
<p>除上下文另有约定外，本协议中下列术语的含义如下：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">术语</th>
<th style="text-align: center;">定义</th>
</tr>
</thead>
<tbody>
<tr>
<td>本平台 /</td>
<td>指由【运营主体名称】（以下简称"运营方"）开发、运营的""软件，包括其小程序、APP、网页版及未来可能开发的其他客户端形态</td>
</tr>
<tr>
<td>用户 / 您</td>
<td>指依据本协议注册、使用本平台服务的自然人</td>
</tr>
<tr>
<td>技术服务</td>
<td>指本平台向用户提供的话录音语音识别、违规行为规则比对、技术分析报告生成等技术服务</td>
</tr>
<tr>
<td>技术分析报告 / 报告</td>
<td>指本平台基于用户上传或授权录制的话录音，经语音识别、规则比对后自动生成的技术分析文件</td>
</tr>
<tr>
<td>录音文件</td>
<td>指用户主动上传或经用户授权由本平台录制的、用户与第三方（含催收方）之间通话过程的音频文件</td>
</tr>
<tr>
<td>敏感个人信息</td>
<td>指一旦泄露或者非法使用，容易导致自然人的人格尊严受到侵害或者人身、财产安全受到危害的个人信息，包括生物识别（声纹）、通话内容、行踪轨迹等</td>
</tr>
<tr>
<td>个人信息处理者</td>
<td>指在个人信息处理活动中自主决定处理目的、处理方式的组织或个人，本协议中即运营方</td>
</tr>
</tbody>
</table>
<p><strong>1.2 解释</strong></p>
<p>本协议各条款标题仅为方便阅读之用，不影响相关条款内容的解释。条款中"包括"一词为列举性表述，不构成对列举范围的限制。</p>
<p><strong>第二条 协议的构成、订立与生效</strong></p>
<p><strong>2.1 协议构成</strong></p>
<p>本协议由本协议正文、《隐私保护指引》、《个人信息收集与使用授权书》、《通话录音与语音识别授权协议》（以下合称"<strong>配套文件</strong>"）共同构成不可分割的整体，共同构成本平台与您之间就您使用本平台服务所达成的完整法律文件。</p>
<p><strong>2.2 协议订立</strong></p>
<p>您通过本平台注册、登录页面勾选同意本协议及配套文件后，本协议即在您与运营方之间成立。勾选行为即视为您已充分阅读、理解并同意接受本协议及配套文件全部条款，无需逐条勾选。</p>
<p><strong>2.3 协议生效</strong></p>
<p>本协议自您点击确认之时起生效，直至您依据本协议约定或法律规定解除或终止本协议时止。</p>
<p><strong>2.4 协议变更</strong></p>
<p>运营方有权根据法律法规变更、业务调整等原因<strong>不定期修改</strong>本协议条款。修改后的协议一经在本平台公示即发生效力。<strong>如您不同意修改后的协议内容，您有权选择停止使用本平台服务并注销账户；如您继续使用本平台服务，则视为您接受修改后的协议</strong>。</p>
<p>运营方将在协议修改生效前<strong>至少7个自然日</strong>通过本平台站内信、APP推送通知等方式向您发出变更通知。</p>
<p><strong>第三条 服务内容与方式</strong></p>
<p><strong>3.1 服务内容</strong></p>
<p>本平台向您提供的技术服务<strong>限于</strong>以下范围：</p>
<blockquote>
<p><strong>1.
录音文件上传与解析</strong>：您可通过本平台上传您本人通话过程中合法取得的录音文件，本平台通过自动化技术对录音进行语音识别、文本转换与内容分析；</p>
<p><strong>2.
规则比对</strong>：本平台依据<strong>现行有效的法律法规、监管规定、行业规范</strong>（包括但不限于《商业银行互联网贷款管理暂行办法》、《银行保险机构消费者权益保护管理办法》、《互联网金融
个人网络消费信贷 贷后催收风控指引》（GB/T
45251-2025）、国家金融监督管理总局及派出机构发布的催收合规文件等）对录音内容进行规则比对，识别其中可能存在的违规情形；</p>
<p><strong>3.
技术分析报告生成</strong>：本平台基于规则比对结果，自动生成包含违规情形提示、违规类型分类、相关法条/规范引用等内容的<strong>技术分析报告</strong>。</p>
</blockquote>
<p><strong>3.2 服务方式</strong></p>
<p>您可以通过以下任一方式使用本平台服务：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">方式</th>
<th style="text-align: center;">说明</th>
</tr>
</thead>
<tbody>
<tr>
<td>方式一：事后上传分析</td>
<td>您在接到催收电话后<strong>主动录音</strong>，事后将录音文件上传至本平台，本平台在合理时间内完成分析并出具报告</td>
</tr>
<tr>
<td>方式二：实时通话识别</td>
<td>您在接到催收电话时通过本平台 APP
启动实时识别功能，本平台在您通话过程中实时识别对方通话内容，并对可能的违规情形进行实时提示</td>
</tr>
</tbody>
</table>
<p><strong>3.3 服务的有限性</strong></p>
<p>您<strong>明确知悉并同意</strong>：</p>
<blockquote>
<p><strong>1.</strong>
本平台出具的<strong>技术分析报告仅供您本人参考</strong>，<strong>不构成法律意见、不构成证据、不替代专业律师的判断</strong>；</p>
<p><strong>2.</strong>
本平台<strong>不对报告内容的准确性、完整性、时效性、可用性</strong>作任何明示或暗示的保证；</p>
<p><strong>3.</strong>
本平台<strong>不参与、不介入</strong>您与任何第三方（含催收方、金融机构、监管机构）之间的任何纠纷、协商、谈判或诉讼；</p>
<p><strong>4.</strong>
本平台<strong>不发起、不代理、不组织</strong>您或任何其他人向任何机构发起投诉、举报、信访、集体维权等行为；</p>
<p><strong>5.</strong>
本平台<strong>不提供</strong>债务优化、停息挂账、征信修复、代理维权等任何金融服务或法律服务。</p>
</blockquote>
<p><strong>第四条 用户资格与账户管理</strong></p>
<p><strong>4.1 用户资格</strong></p>
<p>您确认并承诺：在使用本平台服务时，您是<strong>具有完全民事行为能力的自然人</strong>。<strong>未成年人、限制民事行为能力人</strong>不得注册本平台账户或使用本平台服务。如您不符合上述资格条件，运营方有权立即关闭您的账户，且由此产生的一切法律责任由您及您的监护人承担。</p>
<p><strong>4.2 账户注册</strong></p>
<p>您应当依据本平台注册页面的提示，<strong>提供真实、准确、完整、有效的身份信息</strong>完成注册。<strong>您不得以任何方式冒用他人身份信息注册账户</strong>。</p>
<p><strong>4.3 账户安全</strong></p>
<p>您应当妥善保管您的账户、密码及验证码。<strong>任何通过您账户和密码完成的操作均视为您本人行为，由此产生的一切法律后果由您本人承担</strong>。</p>
<p>如您发现账户被他人冒用、盗用或存在其他安全风险，应当<strong>立即</strong>通过本平台客服渠道通知运营方，并配合运营方采取相应措施。</p>
<p><strong>4.4 账户注销</strong></p>
<p>您有权<strong>随时</strong>通过本平台设置页面申请注销您的账户。运营方将在<strong>15个工作日内</strong>完成核查并处理您的注销申请。账户注销后，本平台将依据《隐私保护指引》对您的个人信息进行删除或匿名化处理，但法律法规另有规定的除外。除账户注销情形外，您在使用本平台服务期间上传的录音文件及生成的技术分析报告的存储期限、到期自动删除机制以及您撤回同意后的数据处理方式，均按照《隐私保护指引》及《通话录音与语音识别授权协议》的相关约定执行。</p>
<p><strong>第五条 用户行为规范与平台中立声明</strong></p>
<p><strong>5.1 合法来源义务</strong></p>
<p>您<strong>承诺并保证</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您上传至本平台的<strong>录音文件均为您本人通话过程中合法取得</strong>，未通过窃听、偷录、非法监控等任何违法方式获取；</p>
<p><strong>2.</strong>
您对录音文件中所涉及的<strong>任何第三方（含催收方）享有合法处理基础</strong>，包括但不限于已经取得对方的同意或符合《个人信息保护法》第13条规定无需取得同意的其他情形；</p>
<p><strong>3.</strong>
录音文件<strong>不包含</strong>任何国家秘密、商业秘密、个人隐私或其他依法受保护的信息，或您已经获得合法授权可进行处理。</p>
</blockquote>
<p><strong>5.2 合规使用义务</strong></p>
<p>您<strong>承诺</strong>不会将本平台服务用于以下任何目的或行为：</p>
<blockquote>
<p><strong>1. 制作、传播任何虚假的违法违规线索或证据</strong>；</p>
<p><strong>2.
以技术分析报告为依据，对金融机构、催收方或任何第三方实施敲诈勒索、胁迫、恐吓、侮辱、诽谤等违法行为</strong>；</p>
<p><strong>3.
组织、教唆、帮助他人实施恶意投诉、群体性投诉、模板化投诉、举报等行为</strong>；</p>
<p><strong>4.
以本平台报告为依据，伪造、变造国家机关公文、证件、印章或公司、企业、事业单位印章</strong>；</p>
<p><strong>5.
以本平台报告为依据，从事任何形式的债务优化、停息挂账、征信修复、代理维权、反催收等黑灰产活动</strong>；</p>
<p><strong>6.
将本平台报告用于任何商业用途</strong>，包括但不限于出售、转让、授权他人使用、用于对外宣传等；</p>
<p><strong>7.
将本平台服务用于任何违反中华人民共和国法律、违背公序良俗、损害国家利益或社会公共利益的目的</strong>；</p>
<p><strong>8.
实施任何干扰、破坏、绕过本平台技术保护措施或安全机制的行为</strong>；</p>
<p><strong>9.
对本平台进行反向工程、反向编译、反汇编或以其他方式试图获取本平台源代码、算法、模型等</strong>。</p>
</blockquote>
<p><strong>5.3 真实性承诺</strong></p>
<p>您<strong>承诺</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您基于本平台报告所作的一切投诉、举报、信访、协商、诉讼等行为，均<strong>基于真实、具体、客观的事实</strong>，<strong>不虚构、不夸大、不歪曲</strong>；</p>
<p><strong>2.</strong> 您对您行为的真实性、合法性承担全部法律责任；</p>
<p><strong>3.</strong>
如您违反本条约定，构成《刑法》相关犯罪（如敲诈勒索罪、诈骗罪、伪造国家机关公文证件印章罪、侵犯公民个人信息罪等）的，运营方有权<strong>立即向公安机关、监管部门举报</strong>并配合调查。</p>
</blockquote>
<p><strong>5.4 反"反催收"专项承诺</strong></p>
<p>您<strong>特别承诺</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您<strong>不会</strong>将本平台出具的技术分析报告<strong>单独或与其他材料组合</strong>，用于向金融机构发起<strong>以减免债务、停止催收为主要目的</strong>的投诉；</p>
<p><strong>2.</strong>
您<strong>不会</strong>通过本平台或基于本平台报告，发起、参与或组织任何形式的<strong>群体性、模板化、批量化</strong>的投诉、举报活动；</p>
<p><strong>3.</strong>
您<strong>知悉</strong>"反催收"黑灰产已被《刑法》《金融违法行为处罚办法》等法律法规所规制，可能涉嫌<strong>敲诈勒索罪、诈骗罪、侵犯公民个人信息罪</strong>等刑事犯罪，运营方对任何"反催收"违法犯罪行为<strong>零容忍</strong>；</p>
<p><strong>4.</strong>
如本平台<strong>合理怀疑</strong>您利用本平台服务从事"反催收"等违法犯罪活动的，本平台有权<strong>立即冻结或终止</strong>您的账户、<strong>拒绝</strong>为您提供服务、<strong>删除</strong>您的相关数据、<strong>向有关部门举报</strong>并配合调查。</p>
</blockquote>
<p><strong>5.5 平台中立声明</strong></p>
<p>本平台就用户自主维权事项，郑重声明如下：</p>
<blockquote>
<p>1. 本平台不发起任何投诉、举报、信访活动；</p>
<p>2.
本平台不组织、不教唆、不帮助、不资助用户参与任何投诉、举报、信访活动；</p>
<p>3.
本平台不代理用户向金融监管部门、消费者权益保护机构、行业自律组织提交任何投诉、举报材料，亦不代为拨打
12378、12315、12321 等投诉举报电话或代为填写举报表格；</p>
<p>4.
本平台不参与用户与金融机构、催收方之间的任何协商、谈判、调解、诉讼活动，亦不为上述活动向金融机构或催收方施加任何影响；</p>
<p>5.
本平台不以投诉、举报为名向用户收取任何费用（合理技术服务费除外），亦不以投诉、举报数量为条件设置任何奖励、返利或补贴；</p>
<p>6.
本平台不出具任何具有法律意见性质的结论，亦不对投诉、举报的受理与处理结果作出任何承诺或保证。</p>
</blockquote>
<p><strong>5.6 技术性协助的范围</strong></p>
<p>本平台可以为您提供的技术性协助仅限于：</p>
<blockquote>
<p>1. 出具技术分析报告；</p>
<p>2. 导出、下载报告文件；</p>
<p>3. 提供相关法律法规条文查询；</p>
<p>4. 提供公开投诉、举报渠道指引。</p>
</blockquote>
<p>本平台绝不提供下列任何协助：</p>
<blockquote>
<p>1.
代为起草、修改以索取财物、减免债务、停止催收为目的的投诉、举报文书；</p>
<p>2. 提供投诉、举报话术、模板或组织化、批量化的投诉方案；</p>
<p>3.
代为联络、施压金融机构或催收机构，或代为进行债务协商、债务处置；</p>
<p>4.
提供或变相提供"代理维权""债务优化""征信修复""停息挂账""反催收"等任何形式的服务；</p>
<p>5. 就投诉、举报的受理与处理结果作出任何承诺或保证。</p>
<p>您基于本平台报告自行决定是否投诉、举报，以及向谁、何时、以何种方式主张权利，并自行承担由此产生的一切后果。</p>
</blockquote>
<p><strong>5.7 平台对"反催收"行为的识别</strong></p>
<p>本平台将基于以下客观行为特征，合理识别并防范"反催收"活动：</p>
<blockquote>
<p>1. 用户批量提交内容高度相似的模板化材料；</p>
<p>2. 用户就同一催收方对不同金融机构发起重复、集中的投诉举报；</p>
<p>3.
用户以本平台报告为依据，提出"减免债务""停止催收""赔偿"等与所述违规情形不直接关联的诉求；</p>
<p>4.
用户同一账户代理多名债务人处理同类事务，或接受他人支付费用后协助其使用本平台服务；</p>
<p>5.
用户在本平台发布"反催收"教程、话术、模板，或招募、组织、教唆他人实施前述行为；</p>
<p>6. 其他符合"反催收"典型行为模式的情形。</p>
<p>出现前款情形的，本平台有权依据本协议第 8.1
条、第九条采取相应处置措施，并有权向公安机关、金融监管部门举报并配合调查。</p>
</blockquote>
<p><strong>第六条 个人信息保护</strong></p>
<p><strong>6.1 个人信息处理</strong></p>
<p>运营方将依据《隐私保护指引》及《个人信息收集与使用授权书》处理您的个人信息。<strong>请您仔细阅读并充分理解上述文件</strong>。</p>
<p><strong>6.2 敏感个人信息</strong></p>
<p>本平台涉及的敏感个人信息处理<strong>特别提示</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您的<strong>声纹</strong>属于生物识别敏感个人信息，本平台将依据《个人信息保护法》第28-29条取得您的<strong>单独同意</strong>后方进行处理；</p>
<p><strong>2.</strong>
录音文件中涉及的通话对方（催收方）的个人信息，本平台将进行自动化去标识化处理，处理标准包括：剔除或替换所有自然人姓名、具体联系电话、工号、微信号等直接标识符，仅保留违规类型分类与所属机构大类信息用于生成技术分析报告。</p>
</blockquote>
<p><strong>6.3 您的权利</strong></p>
<p>您依据《个人信息保护法》享有知情权、决定权、查阅权、复制权、更正权、删除权、撤回同意权等权利。<strong>您行使上述权利的方式、途径详见《隐私保护指引》</strong>。</p>
<p><strong>第七条 知识产权</strong></p>
<p><strong>7.1 平台知识产权</strong></p>
<p>本平台及其背后的所有技术（包括但不限于软件代码、算法、模型、UI
设计、商标、商号、域名等）的一切知识产权及<strong>相关权利</strong>均<strong>归运营方或合法权利人所有</strong>，受《中华人民共和国著作权法》《中华人民共和国专利法》《中华人民共和国商标法》等法律法规的保护。</p>
<p><strong>7.2 用户内容许可</strong></p>
<p>您通过本平台上传的录音文件，<strong>您授予运营方一项非独占、不可转让、仅限本协议目的范围内使用的处理许可</strong>，用于本平台提供技术服务之目的。<strong>该许可在本协议终止后自动终止</strong>。</p>
<p><strong>7.3 报告归属</strong></p>
<p>本平台出具的技术分析报告<strong>著作权归运营方所有</strong>。您在遵守本协议第5.2条约定的前提下，可基于个人参考的目的<strong>查阅、保存、打印</strong>报告内容。</p>
<p><strong>第八条 平台权利与义务</strong></p>
<p><strong>8.1 平台权利</strong></p>
<p>运营方<strong>有权</strong>：</p>
<blockquote>
<p><strong>1.</strong>
依据法律法规、监管要求、技术升级等原因，<strong>随时调整、变更、中断或终止</strong>部分或全部服务内容；</p>
<p><strong>2.</strong>
对用户的账户使用行为进行自动化监测和审查，当系统识别到异常行为特征（包括但不限于单日上传录音超过合理频次阈值、短时间内批量生成报告、同一设备多账户切换操作等）时，运营方有权视情节严重程度采取阶梯式处置措施，包括触发人工复核、限制单日使用次数、暂停服务、终止服务、删除数据、报告监管部门；</p>
<p><strong>3.</strong>
依据本协议约定<strong>向您主张违约责任</strong>。</p>
</blockquote>
<p><strong>8.2 平台义务</strong></p>
<p>运营方<strong>应当</strong>：</p>
<blockquote>
<p><strong>1.</strong> 按照本协议约定提供技术服务；</p>
<p><strong>2.</strong> 依法保护您的个人信息及合法权益；</p>
<p><strong>3. 在您自愿、独立、合法主张自身合法权益时，向您提供本协议第
5.6 条约定的技术性协助。</strong></p>
</blockquote>
<p><strong>8.3 免责声明</strong></p>
<p>基于本协议第8条及前言核心定位声明，本平台仅作为技术识别工具提供服务，在此基础上<strong>运营方不对以下情况承担任何责任</strong>：</p>
<blockquote>
<p><strong>1.</strong>
因<strong>不可抗力</strong>（包括但不限于自然灾害、网络攻击、政策法规重大变化、政府行为等）导致的服务中断或异常；</p>
<p><strong>2.</strong>
因您<strong>自身过错</strong>（包括但不限于违反本协议约定、操作不当、终端设备问题、网络问题等）造成的任何损失；</p>
<p><strong>3.</strong>
因<strong>第三方原因</strong>（包括但不限于电信运营商、第三方软件服务、不可归责于运营方的事件等）导致的服务异常或损失；</p>
<p><strong>4.</strong>
您基于本平台报告所作的<strong>任何决定、行为、纠纷</strong>所产生的任何法律后果；</p>
<p><strong>5.</strong>
报告内容<strong>准确性、完整性、可用性、时效性</strong>的瑕疵或不达预期。</p>
</blockquote>
<p><strong>第九条 违约责任</strong></p>
<p><strong>9.1 您的违约责任</strong></p>
<p>如您违反本协议任一条款，包括但不限于本协议第5条用户行为规范，运营方<strong>有权</strong>：</p>
<blockquote>
<p><strong>1.</strong> 发出警告通知；</p>
<p><strong>2. 暂停或限制</strong>您的账户功能；</p>
<p><strong>3. 终止</strong>您的账户及服务；</p>
<p><strong>4. 删除</strong>您的相关数据；</p>
<p><strong>5.</strong>
依法向<strong>有关部门举报</strong>并配合调查；</p>
<p><strong>6.</strong> 依法向您<strong>主张赔偿责任</strong>。</p>
<p><strong>7．申诉机制：</strong>如您对运营方采取的上述处置措施存有异议，可在收到处置通知后5个自然日内通过本平台客服渠道提交书面申诉及相关证明材料，运营方将在收到完整申诉材料后10个工作日内完成复核并作出最终决定；申诉期间不影响运营方已采取的限制或暂停措施的效力</p>
</blockquote>
<p><strong>9.2 平台违约责任</strong></p>
<p>如运营方违反本协议约定，您有权依据法律法规及本协议约定，要求运营方承担违约责任。<strong>但运营方在本协议项下的赔偿责任总额不超过您在本平台累计实际支付的服务费用</strong>（如有）。</p>
<p><strong>第十条 协议解除与终止</strong></p>
<p><strong>10.1 您的解除权</strong></p>
<p>您<strong>有权</strong>随时停止使用本平台服务并<strong>注销</strong>您的账户，本协议自账户注销之时起终止。</p>
<p><strong>10.2 平台的终止权</strong></p>
<p>发生下列情形之一的，运营方<strong>有权</strong>立即终止本协议：</p>
<blockquote>
<p><strong>1.</strong>
您违反本协议任一条款且经运营方通知后<strong>未在合理期限内改正</strong>的；</p>
<p><strong>2.</strong>
您<strong>实施违法犯罪行为</strong>或<strong>严重违背公序良俗</strong>的行为；</p>
<p><strong>3.</strong>
您<strong>提供虚假身份信息</strong>或<strong>冒用他人身份</strong>的；</p>
<p><strong>4.</strong>
法律法规、监管要求<strong>要求终止</strong>的。</p>
</blockquote>
<p><strong>10.3 协议终止后的处理</strong></p>
<p>本协议终止后：</p>
<blockquote>
<p><strong>1.</strong> 运营方将<strong>停止</strong>向您提供服务；</p>
<p><strong>2.</strong>
运营方将依据《隐私保护指引》<strong>删除或匿名化处理</strong>您的个人信息，但法律法规另有规定的除外；</p>
<p><strong>3.
不影响</strong>本协议中依法应当继续有效的条款（包括但不限于知识产权条款、违约责任条款、争议解决条款、免责条款等）的效力。</p>
</blockquote>
<p><strong>第十一条 通知与送达</strong></p>
<p><strong>11.1 通知方式</strong></p>
<p>运营方将通过以下任一方式向您发出通知：</p>
<blockquote>
<p><strong>1.</strong> 本平台<strong>站内信</strong>、APP
<strong>推送通知</strong>、<strong>短信</strong>；</p>
<p><strong>2.</strong> 您在注册时提供的<strong>电子邮箱</strong>；</p>
<p><strong>3.</strong> 本平台<strong>公告</strong>。</p>
</blockquote>
<p><strong>11.2 送达生效</strong></p>
<p>上述通知<strong>自发送成功之时起视为已送达</strong>。如您未及时查看通知，由此产生的一切后果由您本人承担。</p>
<p><strong>第十二条 法律适用与争议解决</strong></p>
<p><strong>12.1 法律适用</strong></p>
<p>本协议的订立、效力、解释、履行、修改及争议解决均<strong>适用中华人民共和国大陆地区法律</strong>（不含香港特别行政区、澳门特别行政区、台湾地区法律）。</p>
<p><strong>12.2 争议解决</strong></p>
<p>本协议项下发生的任何争议，双方应<strong>首先友好协商解决</strong>。协商不成的，任何一方均<strong>同意</strong>将争议提交<strong>运营方所在地有管辖权的人民法院</strong>通过诉讼方式解决。</p>
<p><strong>第十三条 其他</strong></p>
<p><strong>13.1 完整协议</strong></p>
<p>本协议与配套文件共同构成本平台与您就本协议标的达成的<strong>完整协议</strong>，<strong>取代</strong>双方此前就相同标的达成的所有口头或书面协议、理解和安排。</p>
<p><strong>13.2 可分割性</strong></p>
<p>如本协议任一条款被有管辖权的人民法院认定为<strong>无效</strong>或<strong>不可执行</strong>，不影响其他条款的效力，其他条款继续有效。</p>
<p><strong>13.3 权利保留</strong></p>
<p>运营方未行使或延迟行使本协议项下任何权利，不构成对该权利的放弃。</p>
<p><strong>13.4 协议文本</strong></p>
<p>本协议以<strong>简体中文</strong>为准文本。本协议如有<strong>多个语言版本</strong>，<strong>简体中文版本为正式版本</strong>，其他语言版本仅供参考。</p>
<p><strong>第十四条 运营方信息</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>运营主体名称</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>统一社会信用代码</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>注册地址</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服电话</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服邮箱</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>个人信息保护负责人</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>DPO 联系方式</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>确认声明</strong></p>
<p>您通过本平台注册/登录页面勾选本文件，即表示您已充分阅读、理解并同意本文件全部条款；勾选本文件即视为同意本文件项下的各项条款，无需逐条勾选。</p>
<p><strong>【协议正文至此结束】</strong></p>',1,1,NOW(3),NOW(3)),
('privacy_guide','隐私保护指引','<p><strong>还了吗 隐私保护指引</strong></p>
<blockquote>
<p><strong>版本编号</strong>：V1.0</p>
<p><strong>更新日期：2026年9月16日</strong></p>
<p><strong>生效日期：2026年9月16日</strong></p>
<p><strong>文件性质</strong>：用户注册强制勾选文件</p>
<p><strong>法律依据</strong>：《中华人民共和国个人信息保护法》《中华人民共和国网络安全法》《中华人民共和国数据安全法》《App收集使用个人信息最小必要评估规范》（TTAF
077系列）</p>
</blockquote>
<p><strong>引言</strong></p>
<p><strong>【特别提示】</strong>
欢迎您使用"还了吗"（以下简称"本平台"）服务。本平台<strong>由衷尊重并保护您的个人信息及隐私安全</strong>。本《隐私保护指引》将帮助您了解：</p>
<blockquote>
<p><strong>•</strong> 本平台<strong>收集哪些</strong>个人信息；</p>
<p><strong>•</strong> 本平台<strong>为何收集</strong>这些信息；</p>
<p><strong>•</strong>
本平台<strong>如何存储、使用、共享、转让、公开披露</strong>这些信息；</p>
<p><strong>•</strong> 您<strong>如何行使</strong>对个人信息的权利；</p>
<p><strong>•</strong> 本平台<strong>如何保护</strong>您的信息安全。</p>
</blockquote>
<p><strong>请您仔细阅读本指引，在充分理解后再行决定是否使用本平台服务。</strong></p>
<p><strong>【核心定位声明】</strong>
本平台仅为债务人提供技术识别工具，<strong>不涉及</strong>任何金融业务、法律服务、催收业务。本平台对您个人信息的处理，<strong>严格遵循"最小必要"原则</strong>——仅处理为您提供技术服务所必需的信息。</p>
<p><strong>第一部分 个人信息处理者信息</strong></p>
<p><strong>1.1 个人信息处理者基本情况</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>运营主体名称</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>统一社会信用代码</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>注册地址</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>办公地址</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>常用办公地址</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>1.2 个人信息保护负责人</strong></p>
<p>依据《个人信息保护法》第52条，本平台已<strong>指定个人信息保护负责人</strong>，负责本平台个人信息处理活动及保护措施的监督：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>姓名</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>职务</td>
<td>个人信息保护负责人</td>
</tr>
<tr>
<td>联系电话</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>电子邮箱</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>1.3 数据保护官（DPO）</strong></p>
<p>依据《个人信息保护法》第52条及《个人信息保护合规审计办法》（2025年5月1日施行），<strong>本平台处理个人信息达到国家网信部门规定数量</strong>的，已<strong>指定数据保护官（DPO）</strong>：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>姓名</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>联系方式</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>汇报对象</td>
<td>公司董事会（或相应决策机构）</td>
</tr>
</tbody>
</table>
<p><strong>第二部分 适用范围</strong></p>
<p>本指引<strong>适用于</strong>您通过本平台小程序、APP、网页版及未来可能开发的其他客户端形态使用本平台服务时所涉及的<strong>个人信息处理活动</strong>。</p>
<p>本指引<strong>不适用于</strong>：</p>
<blockquote>
<p><strong>1.</strong>
第三方通过本平台向您提供的服务（具体见本指引"第三方共享与链接"部分）；</p>
<p><strong>2.</strong>
您通过本平台跳转至第三方网站、应用后所进行的个人信息处理活动（届时请阅读第三方的隐私政策）。</p>
</blockquote>
<p><strong>第三部分 本平台可能收集的个人信息</strong></p>
<p>本平台<strong>严格遵循"最小必要"原则</strong>，<strong>仅收集为您提供技术服务所必需的个人信息</strong>。本平台判断信息收集必要性的标准如下：①该信息是实现相关服务功能的不可替代要素；②拒绝提供该信息将直接导致相关功能无法运行；③存在更少信息量即可完成服务的替代方案时，优先采用替代方案。</p>
<p>以下为本平台可能收集的信息类型、收集目的、收集方式、敏感程度等：</p>
<p><strong>3.1 账户注册与登录</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">收集目的</th>
<th style="text-align: center;">收集方式</th>
<th style="text-align: center;">敏感程度</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>手机号码</td>
<td>用于注册账户、身份验证、账户安全</td>
<td>用户主动填写</td>
<td>一般</td>
<td>账户存续期间</td>
</tr>
<tr>
<td>短信验证码</td>
<td>用于验证手机号真实性</td>
<td>用户主动填写</td>
<td>一般</td>
<td>当次有效，不留存</td>
</tr>
<tr>
<td>密码（加密存储）</td>
<td>用于账户登录</td>
<td>用户主动设置</td>
<td>一般</td>
<td>账户存续期间</td>
</tr>
<tr>
<td>第三方账号信息（微信/支付宝/Apple ID 等）</td>
<td>用于第三方快捷登录</td>
<td>用户主动授权</td>
<td>一般</td>
<td>账户存续期间</td>
</tr>
<tr>
<td>设备信息（设备型号、操作系统版本、设备识别码）</td>
<td>用于安全风控、版本适配</td>
<td>自动采集</td>
<td>一般</td>
<td>2年</td>
</tr>
</tbody>
</table>
<p><strong>3.2 录音文件上传分析（事后分析方式）</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">收集目的</th>
<th style="text-align: center;">收集方式</th>
<th style="text-align: center;">敏感程度</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>录音文件（用户主动上传）</td>
<td>用于语音识别、规则比对、报告生成</td>
<td>用户主动上传</td>
<td><strong>敏感（含第三方个人信息）</strong></td>
<td>分析完成后<strong>90日</strong>自动删除</td>
</tr>
<tr>
<td>录音元数据（时长、文件大小、录音时间）</td>
<td>用于服务统计、质量分析</td>
<td>自动采集</td>
<td>一般</td>
<td>2年</td>
</tr>
<tr>
<td>报告内容（违规类型、法条引用、识别结果）</td>
<td>用于反馈给用户</td>
<td>自动生成</td>
<td>一般</td>
<td>2年</td>
</tr>
</tbody>
</table>
<p><strong>3.3 实时通话识别（实时识别方式）</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">收集目的</th>
<th style="text-align: center;">收集方式</th>
<th style="text-align: center;">敏感程度</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>实时音频流（用户授权时）</td>
<td>用于实时识别违规情形</td>
<td>用户主动启动</td>
<td><strong>敏感</strong></td>
<td><strong>不永久存储</strong>；识别完成后立即销毁原始音频流，仅保留分析结果</td>
</tr>
<tr>
<td>识别结果文本</td>
<td>用于实时提示用户</td>
<td>自动生成</td>
<td>一般</td>
<td>通话结束后<strong>90日</strong>自动删除</td>
</tr>
</tbody>
</table>
<p><strong>3.4 客户支持与争议处理</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">收集目的</th>
<th style="text-align: center;">收集方式</th>
<th style="text-align: center;">敏感程度</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>您主动提供的信息（联系方式、问题描述、相关材料）</td>
<td>用于响应您的请求、处理争议</td>
<td>用户主动提供</td>
<td>一般</td>
<td>争议处理完毕后<strong>2年</strong></td>
</tr>
</tbody>
</table>
<p><strong>3.5 设备权限调用</strong></p>
<p>本平台<strong>仅在为您提供相应服务所必需时</strong>，才向您申请相应设备权限。<strong>您可以随时在系统设置中撤回授权</strong>：</p>
<table>
<colgroup>
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">权限</th>
<th style="text-align: center;">调用目的</th>
<th style="text-align: center;">触发时机</th>
<th style="text-align: center;">是否必需</th>
</tr>
</thead>
<tbody>
<tr>
<td>麦克风权限</td>
<td>用于实时识别功能、录制上传说明音频</td>
<td>用户启动实时识别 / 主动录制时</td>
<td>否（仅在使用相应功能时）</td>
</tr>
<tr>
<td>电话权限</td>
<td>用于实时识别功能识别通话内容</td>
<td>用户启动实时识别时</td>
<td>否</td>
</tr>
<tr>
<td>存储权限</td>
<td>用于上传本地录音文件</td>
<td>用户主动上传录音时</td>
<td>否</td>
</tr>
<tr>
<td>通知权限</td>
<td>用于服务通知、违规提示</td>
<td>用户首次启动 APP 时</td>
<td>否</td>
</tr>
<tr>
<td>位置权限（粗略）</td>
<td>用于辅助判断催收行为发生地（可选项）</td>
<td>用户主动选择时</td>
<td>否</td>
</tr>
<tr>
<td>相机权限</td>
<td>用于问题截图反馈</td>
<td>用户主动调用时</td>
<td>否</td>
</tr>
</tbody>
</table>
<blockquote>
<p><strong>【权限撤回说明】</strong>
您拒绝提供上述任何权限，<strong>本平台不会因此拒绝为您提供基础服务</strong>（实时识别、上传录音等需要相应权限的功能除外）。</p>
</blockquote>
<p><strong>3.6 Cookie 与同类技术</strong></p>
<p>本平台在网页版中<strong>可能使用</strong>Cookie、Local Storage
等同类技术，用于：</p>
<blockquote>
<p><strong>•</strong> 保持您的登录状态；</p>
<p><strong>•</strong> 记忆您的偏好设置；</p>
<p><strong>•</strong> 统计访问量、优化产品体验。</p>
</blockquote>
<p><strong>您可以通过浏览器设置管理或清除这些技术</strong>。但请注意，如您选择拒绝或清除，可能无法正常使用部分功能。</p>
<p><strong>第四部分 个人信息处理目的与方式</strong></p>
<p><strong>4.1 处理目的</strong></p>
<p>本平台收集您的个人信息，<strong>仅用于</strong>以下目的：</p>
<blockquote>
<p><strong>1.
提供并优化技术服务</strong>：包括录音分析、规则比对、报告生成、实时识别；</p>
<p><strong>2. 账户管理与安全保障：</strong></p>
<p><strong>3. 客户支持：</strong></p>
<p><strong>4. 法律法规要求：</strong></p>
</blockquote>
<p><strong>4.2 处理方式</strong></p>
<p>本平台对您个人信息的处理方式<strong>包括</strong>：收集、存储、使用、加工、传输、提供、公开、删除等。<strong>所有处理活动均在中华人民共和国境内进行</strong>。</p>
<p><strong>4.3 自动化决策</strong></p>
<p>本平台在为您提供违规情形识别时，<strong>可能涉及自动化决策</strong>——即通过算法模型自动判定录音内容是否触犯相应规则。<strong>您有权</strong>：</p>
<blockquote>
<p><strong>•</strong>
要求本平台<strong>说明</strong>自动化决策的逻辑；</p>
<p><strong>• 拒绝</strong>仅通过自动化决策方式作出的决定；</p>
<p><strong>•</strong>
通过本平台客服渠道<strong>投诉</strong>自动化决策结果。</p>
</blockquote>
<p><strong>4.4 个人信息保护影响评估</strong></p>
<p>依据《个人信息保护法》第55条，本平台在<strong>处理敏感个人信息、进行自动化决策、对外提供个人信息</strong>前，已<strong>事前进行个人信息保护影响评估</strong>（PIPIA），并<strong>留存评估记录</strong>备查。</p>
<p><strong>第五部分 敏感个人信息处理</strong></p>
<p><strong>5.1 敏感个人信息范围</strong></p>
<p>依据《个人信息保护法》第28条，本平台处理的<strong>敏感个人信息</strong>包括：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">敏感个人信息类型</th>
<th style="text-align: center;">涉及场景</th>
</tr>
</thead>
<tbody>
<tr>
<td>生物识别信息（声纹）</td>
<td>实时识别功能（用于区分说话人）</td>
</tr>
<tr>
<td>通话内容</td>
<td>录音分析、实时识别</td>
</tr>
<tr>
<td>行踪轨迹（可选）</td>
<td>用户主动提供时</td>
</tr>
</tbody>
</table>
<p><strong>5.2 单独同意</strong></p>
<p>依据《个人信息保护法》第29条，本平台处理您的敏感个人信息<strong>均已取得您的单独同意</strong>。<strong>未经您的单独同意，本平台不会处理您的敏感个人信息</strong>。</p>
<p><strong>5.3 处理的必要性</strong></p>
<p>本平台处理您的敏感个人信息<strong>均为提供技术服务所必需</strong>。具体必要性说明：</p>
<blockquote>
<p><strong>•
声纹识别</strong>：用于在多人通话场景中区分催收方与债务人，提高识别准确度；</p>
<p><strong>•
通话内容</strong>：是违规识别的核心处理对象，<strong>无法通过其他方式替代</strong>。</p>
</blockquote>
<p><strong>5.4 严格保护措施</strong></p>
<p>本平台对敏感个人信息采取<strong>严格保护措施</strong>：</p>
<blockquote>
<p><strong>• 加密存储</strong>：使用 AES-256 等行业标准加密算法；</p>
<p><strong>• 加密传输</strong>：使用 TLS 1.2 及以上版本；</p>
<p><strong>•
访问控制</strong>：仅授权人员因工作需要方可访问，且<strong>留有访问日志</strong>；</p>
<p><strong>•
最小化原则</strong>：仅处理必要的敏感信息，分析完成后<strong>及时删除</strong>；</p>
<p><strong>•
去标识化处理</strong>：录音中的催收方信息在报告中<strong>仅显示违规类型与所属机构大类</strong>，不显示员工姓名、具体号码等可识别信息。</p>
</blockquote>
<p><strong>第六部分 个人信息共享、转让、公开披露</strong></p>
<p><strong>6.1 共享</strong></p>
<p><strong>原则</strong>：本平台<strong>不会与任何第三方共享您的个人信息</strong>，<strong>除非</strong>：</p>
<blockquote>
<p><strong>1. 取得您的单独同意</strong>；</p>
<p><strong>2. 法律法规或主管部门要求</strong>。</p>
</blockquote>
<p><strong>目前涉及的可能共享情形</strong>：</p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">共享场景</th>
<th style="text-align: center;">共享对象</th>
<th style="text-align: center;">共享信息</th>
<th style="text-align: center;">共享目的</th>
<th style="text-align: center;">您的权利</th>
</tr>
</thead>
<tbody>
<tr>
<td>统计分析（去标识化）</td>
<td>第三方数据分析服务商</td>
<td>不可识别个人身份的统计信息</td>
<td>服务优化</td>
<td>您可拒绝</td>
</tr>
<tr>
<td>法律法规要求</td>
<td>司法机关、行政机关</td>
<td>依法需要提供的信息</td>
<td>配合调查</td>
<td>法定</td>
</tr>
</tbody>
</table>
<p><strong>6.2 转让</strong></p>
<p><strong>原则</strong>：本平台<strong>不会将您的个人信息转让给任何第三方</strong>，<strong>除非</strong>：</p>
<blockquote>
<p><strong>1. 取得您的单独同意</strong>；</p>
<p><strong>2.
涉及合并、收购、资产转让</strong>——如发生此类情形，本平台会<strong>提前通知您</strong>，并要求新的持有方继续以不低于本指引的标准保护您的个人信息。</p>
</blockquote>
<p><strong>6.3 公开披露</strong></p>
<p><strong>原则</strong>：本平台<strong>不会公开披露您的个人信息</strong>，<strong>除非</strong>：</p>
<blockquote>
<p><strong>1. 取得您的单独同意</strong>；</p>
<p><strong>2. 法律法规或主管部门要求</strong>。</p>
</blockquote>
<p><strong>第七部分 第三方 SDK 与服务</strong></p>
<p>本平台可能<strong>嵌入第三方
SDK</strong>（软件开发工具包）以实现某些功能。<strong>未经您的同意，第三方
SDK 不会收集您的个人信息</strong>。本平台对第三方 SDK
的收集行为<strong>不承担责任</strong>，请您同时关注第三方 SDK
运营方的隐私政策。如第三方SDK违反相关规定收集您的个人信息，本平台将依法承担相应责任并协助您维权。</p>
<p><strong>7.1 第三方 SDK 清单</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">SDK 名称</th>
<th style="text-align: center;">运营方</th>
<th style="text-align: center;">使用目的</th>
<th style="text-align: center;">收集信息类型</th>
<th style="text-align: center;">隐私政策链接</th>
</tr>
</thead>
<tbody>
<tr>
<td>微信开放平台 SDK</td>
<td>深圳市腾讯计算机系统有限公司</td>
<td>微信快捷登录、分享</td>
<td>微信账号信息、头像昵称、设备信息</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>支付宝开放平台 SDK</td>
<td>蚂蚁科技集团股份有限公司</td>
<td>支付宝快捷登录、支付（如有）</td>
<td>支付宝账号信息</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>极光推送 SDK</td>
<td>深圳市和讯华谷信息技术有限公司</td>
<td>消息推送</td>
<td>设备信息、应用信息</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>友盟统计 SDK</td>
<td>友盟同欣（北京）科技有限公司</td>
<td>数据统计分析（去标识化）</td>
<td>设备信息、使用行为（去标识化）</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>声网 Agora SDK（实时识别时）</td>
<td>上海兆言网络科技有限公司</td>
<td>实时音频传输</td>
<td>实时音频流</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>阿里云对象存储 SDK</td>
<td>阿里云计算有限公司</td>
<td>录音文件存储</td>
<td>录音文件、报告文件</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<blockquote>
<p><strong>【特别说明】</strong> 上述第三方 SDK
清单为<strong>示例</strong>，实际使用以您使用本平台时的<strong>实时 SDK
清单</strong>为准。本平台将在 APP 内"设置 -
第三方共享清单"页面<strong>实时更新</strong>第三方 SDK 清单。</p>
</blockquote>
<p><strong>第八部分 个人信息存储</strong></p>
<p><strong>8.1 存储地点</strong></p>
<p>您的个人信息<strong>均存储于中华人民共和国境内的服务器</strong>。<strong>未经您的单独同意或法律法规要求，本平台不会将您的个人信息传输至境外</strong>。</p>
<p><strong>8.2 存储期限</strong></p>
<table>
<colgroup>
<col style="width: 33%" />
<col style="width: 33%" />
<col style="width: 33%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">存储期限</th>
<th style="text-align: center;">期限届满后处理</th>
</tr>
</thead>
<tbody>
<tr>
<td>账户信息</td>
<td>账户存续期间</td>
<td>注销后 30 日内删除</td>
</tr>
<tr>
<td>录音文件（分析完成后）</td>
<td><strong>90日</strong></td>
<td>自动删除</td>
</tr>
<tr>
<td>实时识别结果</td>
<td>通话结束后<strong>90日</strong></td>
<td>自动删除</td>
</tr>
<tr>
<td>报告内容</td>
<td><strong>2年</strong></td>
<td>自动删除或匿名化</td>
</tr>
<tr>
<td>设备日志、操作日志</td>
<td><strong>2年</strong></td>
<td>自动删除</td>
</tr>
<tr>
<td>法律法规要求保留的信息</td>
<td>按法定要求</td>
<td>法定</td>
</tr>
</tbody>
</table>
<p><strong>8.3 存储安全</strong></p>
<p>本平台采用<strong>以下措施</strong>保护您的个人信息存储安全：</p>
<blockquote>
<p><strong>• 物理安全</strong>：服务器部署在具备 T3+
等级以上的专业数据中心；</p>
<p><strong>• 网络安全</strong>：部署 WAF、防火墙、入侵检测系统；</p>
<p><strong>•
数据加密</strong>：敏感信息加密存储，加密密钥与数据分离管理；</p>
<p><strong>•
访问控制</strong>：最小权限原则，<strong>双人复核</strong>机制；</p>
<p><strong>• 备份与容灾</strong>：异地多活、定期备份；</p>
<p><strong>• 人员管理</strong>：与员工签订保密协议，定期培训。</p>
</blockquote>
<p><strong>第九部分 您的权利</strong></p>
<p>依据《个人信息保护法》第44-50条，您对您的个人信息享有以下权利：</p>
<p><strong>9.1 知情权、决定权</strong></p>
<p>您有权<strong>了解</strong>本平台对您个人信息的处理情况，并<strong>自主决定</strong>是否同意本平台处理您的个人信息。</p>
<p><strong>9.2 查阅权、复制权</strong></p>
<p>您有权<strong>查阅、复制</strong>您的个人信息。您可通过 APP 内"我的 -
个人信息"页面查阅您的账户信息、报告记录等。如需复制，可通过客服渠道提出申请。</p>
<p><strong>9.3 更正权、补充权</strong></p>
<p>您发现您的个人信息<strong>不准确或不完整</strong>时，有权<strong>要求本平台更正、补充</strong>。您可通过
APP 内相应功能或客服渠道提出。</p>
<p><strong>9.4 删除权</strong></p>
<p>在下列情形之一发生时，您有权<strong>要求本平台删除</strong>您的个人信息：</p>
<blockquote>
<p><strong>1.</strong>
处理目的已实现、无法实现或者为实现处理目的不再必要；</p>
<p><strong>2.</strong>
本平台停止提供产品或者服务，或者保存期限已届满；</p>
<p><strong>3.</strong> 您<strong>撤回同意</strong>；</p>
<p><strong>4.</strong>
本平台违反法律、行政法规或者违反约定处理个人信息；</p>
<p><strong>5.</strong> 法律法规规定的其他情形。</p>
</blockquote>
<p><strong>9.5 撤回同意权</strong></p>
<p>您有权<strong>随时撤回同意</strong>。您撤回同意的，<strong>不影响</strong>撤回前基于您的同意已经进行的个人信息处理活动的效力。</p>
<p>撤回方式：</p>
<blockquote>
<p><strong>•</strong> APP 内"设置 - 隐私设置"页面；</p>
<p><strong>•</strong> 客服渠道。</p>
</blockquote>
<p><strong>9.6 拒绝权</strong></p>
<p>您有权<strong>限制或拒绝</strong>本平台对您的个人信息进行处理。但请注意，拒绝可能导致<strong>相应功能无法使用</strong>。</p>
<p><strong>9.7 可携带权</strong></p>
<p>在符合国家网信部门规定条件的情况下，您有权<strong>请求将您的个人信息转移至您指定的个人信息处理者</strong>。</p>
<p><strong>9.8 申请响应时限</strong></p>
<p>本平台将在<strong>收到您的申请之日起15个工作日内</strong>作出响应。如情况复杂，可延长至<strong>30个工作日</strong>，并<strong>告知您延长理由</strong>。</p>
<p><strong>9.9 投诉举报权</strong></p>
<p>如您认为本平台对您个人信息的处理<strong>违反法律法规</strong>或本指引，您有权：</p>
<blockquote>
<p><strong>•</strong>
向<strong>本平台个人信息保护负责人</strong>投诉（联系方式见第1.2条）；</p>
<p><strong>•</strong>
向<strong>网信、市场监督管理部门</strong>等履行个人信息保护职责的部门投诉；</p>
<p><strong>•</strong> 向<strong>人民法院</strong>提起诉讼。</p>
</blockquote>
<p><strong>第十部分 未成年人保护</strong></p>
<p><strong>10.1 未成年人个人信息特别保护</strong></p>
<p>本平台<strong>不向未成年人提供服务</strong>。如您是未成年人（<strong>未满18周岁</strong>），<strong>请勿注册本平台账户</strong>。</p>
<p><strong>10.2 误注册处理</strong></p>
<p>如您发现您的未成年子女<strong>未经您的同意</strong>注册了本平台账户，您有权<strong>联系本平台</strong>要求<strong>删除相关账户及个人信息</strong>。</p>
<p><strong>10.3 联系方式</strong></p>
<p>发现未成年人误注册情形的，请通过本指引第1.2条联系方式联系本平台。</p>
<p><strong>第十一部分 隐私指引的修订</strong></p>
<p><strong>11.1 修订权</strong></p>
<p>本平台<strong>有权</strong>根据法律法规变更、业务调整等原因<strong>不定期修订</strong>本指引。</p>
<p><strong>11.2 通知方式</strong></p>
<p>重大修订时，本平台将通过<strong>APP
推送、站内信、短信</strong>等方式向您发出通知，<strong>告知您修订内容、生效时间</strong>。</p>
<p><strong>11.3 您的权利</strong></p>
<p>如您不同意修订后的指引，您有权<strong>选择停止使用本平台服务</strong>。如您继续使用，则视为您接受修订后的指引。</p>
<p><strong>第十二部分 联系我们</strong></p>
<p><strong>12.1 咨询、投诉渠道</strong></p>
<p>如您对本指引或您的个人信息处理有任何疑问、意见或建议，请通过以下方式联系我们：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">联系方式</th>
<th style="text-align: center;">详情</th>
</tr>
</thead>
<tbody>
<tr>
<td>客服电话</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服邮箱</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>在线客服</td>
<td>APP 内"我的 - 在线客服"</td>
</tr>
<tr>
<td>办公地址</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>12.2 个人信息保护负责人联系方式</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">联系方式</th>
<th style="text-align: center;">详情</th>
</tr>
</thead>
<tbody>
<tr>
<td>姓名</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>电子邮箱</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>联系电话</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>12.3 响应时效</strong></p>
<p>我们将在<strong>收到您的反馈之日起15个工作日内</strong>回复处理意见或结果。</p>
<p><strong>第十三部分 附则</strong></p>
<p><strong>13.1 协议冲突</strong></p>
<p>本指引与《还了吗软件许可及服务协议》及其配套文件<strong>不一致的，以本指引为准</strong>（仅就个人信息保护相关事项而言）。</p>
<p><strong>13.2 法律适用与争议解决</strong></p>
<p>本指引的订立、效力、解释、履行、修改及争议解决<strong>均适用中华人民共和国大陆地区法律</strong>。<strong>争议</strong>首先通过<strong>协商</strong>解决；协商不成的，提交<strong>运营方所在地有管辖权的人民法院</strong>通过诉讼方式解决。</p>
<p><strong>13.3 解释权</strong></p>
<p>本指引的最终解释权<strong>归运营方所有</strong>。</p>
<p><strong>确认声明</strong></p>
<p>您通过本平台注册/登录页面勾选本文件，即表示您已充分阅读、理解并同意本文件全部条款；勾选本文件即视为同意本文件项下的各项条款，无需逐条勾选。</p>
<p>如本文件涉及敏感个人信息处理，本平台将另行通过独立勾选项取得您的单独同意。</p>
<p><strong>【隐私保护指引正文至此结束】</strong></p>',1,2,NOW(3),NOW(3)),
('personal_info_auth','个人信息收集与使用授权书','<p><strong>还了吗 个人信息收集与使用授权书</strong></p>
<blockquote>
<p><strong>版本编号</strong>：V1.0</p>
<p><strong>更新日期：2026年9月16日</strong></p>
<p><strong>生效日期：2026年9月16日</strong></p>
<p><strong>文件性质</strong>：用户注册强制勾选文件</p>
<p><strong>法律依据</strong>：《个人信息保护法》第13、14、28、29条；《App收集使用个人信息最小必要评估规范
总则》（TTAF 077.1-2021）及《录音信息》（TTAF 077.10-2021）</p>
</blockquote>
<p><strong>【重要提示】</strong></p>
<p><strong>请您仔细阅读本授权书全部内容后再行勾选确认。</strong>
您的勾选确认行为将构成您对本授权书全部条款的<strong>真实意思表示</strong>。</p>
<p>依据《个人信息保护法》及《App收集使用个人信息最小必要评估规范·录音信息》，本平台<strong>对您个人信息的处理必须取得您的单独、明示同意</strong>。本授权书与《还了吗隐私保护指引》共同构成本平台处理您个人信息的法律基础。</p>
<p><strong>您有权拒绝本授权，但拒绝授权可能导致您无法使用本平台的相应功能或全部服务。</strong>
您<strong>可以随时撤回</strong>您的授权，但<strong>不影响撤回前</strong>基于您的授权已经进行的个人信息处理活动的效力。</p>
<p><strong>第一条 授权人基本信息</strong></p>
<p>请您用您真实的个人信息在本平台注册，在本平台的注册信息</p>
<p><strong>第二条 授权范围</strong></p>
<p><strong>2.1
强制授权项（您必须授权方可使用本平台基础服务）</strong></p>
<table>
<colgroup>
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
<col style="width: 20%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">序号</th>
<th style="text-align: center;">授权事项</th>
<th style="text-align: center;">处理目的</th>
<th style="text-align: center;">处理方式</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>1</td>
<td><strong>手机号码</strong></td>
<td>注册账户、身份验证、账户安全</td>
<td>收集、存储、使用</td>
<td>账户存续期间</td>
</tr>
<tr>
<td>2</td>
<td><strong>设备信息</strong>（设备型号、操作系统版本、设备识别码）</td>
<td>安全风控、版本适配</td>
<td>收集、存储、使用</td>
<td>2年</td>
</tr>
<tr>
<td>3</td>
<td><strong>账户密码</strong>（加密存储）</td>
<td>账户登录、身份验证</td>
<td>收集、加密存储、使用</td>
<td>账户存续期间</td>
</tr>
<tr>
<td>4</td>
<td><strong>日志信息</strong>（操作日志、网络日志）</td>
<td>安全保障、问题排查、合规审计</td>
<td>自动记录、存储</td>
<td>2年</td>
</tr>
</tbody>
</table>
<p><strong>2.2
业务功能授权项（您必须勾选同意方可使用相应业务功能）</strong></p>
<p><strong>2.2.1 录音文件上传分析（事后分析方式）——**必选**</strong></p>
<table>
<colgroup>
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">授权事项</th>
<th style="text-align: center;">处理目的</th>
<th style="text-align: center;">处理方式</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>您主动上传的录音文件</td>
<td>语音识别、规则比对、报告生成</td>
<td>收集、存储、使用、删除</td>
<td>分析完成后<strong>90日</strong>自动删除</td>
</tr>
<tr>
<td>录音元数据（时长、文件大小、录音时间）</td>
<td>服务统计、质量分析</td>
<td>自动采集、存储</td>
<td>2年</td>
</tr>
<tr>
<td>报告识别结果</td>
<td>反馈给用户</td>
<td>自动生成、存储</td>
<td>2年</td>
</tr>
</tbody>
</table>
<p><strong>2.2.2 实时通话识别（实时识别方式）——**必选**</strong></p>
<table>
<colgroup>
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">授权事项</th>
<th style="text-align: center;">处理目的</th>
<th style="text-align: center;">处理方式</th>
<th style="text-align: center;">存储期限</th>
</tr>
</thead>
<tbody>
<tr>
<td>实时音频流（含声纹等敏感个人信息）</td>
<td>实时识别违规情形</td>
<td>收集（不永久存储）、使用</td>
<td><strong>不永久存储</strong>；识别完成后立即销毁原始音频流</td>
</tr>
<tr>
<td>识别结果文本</td>
<td>实时提示用户</td>
<td>自动生成、存储</td>
<td>通话结束后<strong>90日</strong>自动删除</td>
</tr>
</tbody>
</table>
<p><strong>第三条 个人信息处理目的</strong></p>
<p>本平台<strong>仅在以下目的范围内</strong>处理您的个人信息：</p>
<blockquote>
<p><strong>1.
提供并优化还了吗技术服务</strong>：包括录音分析、实时识别、报告生成、违法违规线索识别；</p>
<p><strong>2. 账户管理与安全保障：</strong></p>
<p><strong>3.
客户支持与争议处理：</strong>响应您的咨询、投诉、建议；</p>
<p><strong>4. 法律法规要求：</strong></p>
</blockquote>
<p><strong>【严禁使用条款】</strong>
本平台<strong>严格禁止</strong>将您的个人信息用于以下目的：</p>
<blockquote>
<p><strong>•</strong>
任何形式的"反催收"、"代理维权"、"债务优化"、"征信修复"等黑灰产活动；</p>
<p><strong>•</strong>
任何形式的商业营销、推广、推销（除非您另行明示同意）；</p>
<p><strong>•</strong>
任何形式的"画像"、"标签"、"评分"等用户分类行为（仅限内部风控的除外）；</p>
<p><strong>•</strong>
任何形式的<strong>对外提供、共享、转让</strong>（法律要求或您另行明示同意的除外）。</p>
</blockquote>
<p><strong>第四条 个人信息处理方式</strong></p>
<p><strong>4.1 处理方式</strong></p>
<p>本平台对您个人信息的处理方式<strong>包括</strong>：收集、存储、使用、加工、传输、删除等。</p>
<p><strong>4.2 自动化决策</strong></p>
<p>本平台在为您提供违规情形识别服务时，<strong>可能涉及自动化决策</strong>。您<strong>有权</strong>：</p>
<blockquote>
<p><strong>1.</strong>
要求本平台<strong>说明</strong>自动化决策的逻辑；</p>
<p><strong>2. 拒绝</strong>仅通过自动化决策方式作出的决定；</p>
<p><strong>3.</strong>
通过本平台客服渠道<strong>投诉</strong>自动化决策结果。</p>
</blockquote>
<p><strong>4.3 存储地点</strong></p>
<p>您的个人信息<strong>均存储于中华人民共和国境内的服务器</strong>。<strong>未经您的单独同意，本平台不会将您的个人信息传输至境外</strong>。</p>
<p><strong>第五条 个人信息共享、转让、公开披露</strong></p>
<p><strong>5.1 共享承诺</strong></p>
<p><strong>未经您的单独明示同意，本平台不会与任何第三方共享您的个人信息</strong>。<strong>法律法规或主管部门要求共享的除外</strong>。</p>
<p><strong>5.2 可能涉及共享的情形</strong></p>
<table>
<colgroup>
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
<col style="width: 25%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">共享场景</th>
<th style="text-align: center;">共享对象</th>
<th style="text-align: center;">共享信息</th>
<th style="text-align: center;">您的权利</th>
</tr>
</thead>
<tbody>
<tr>
<td>统计分析（去标识化）</td>
<td>第三方数据分析服务商</td>
<td>不可识别个人身份的统计信息</td>
<td>您可拒绝</td>
</tr>
<tr>
<td>配合调查</td>
<td>司法机关、行政机关</td>
<td>法律法规要求的信息</td>
<td>法定</td>
</tr>
</tbody>
</table>
<p><strong>5.3 转让承诺</strong></p>
<p><strong>未经您的单独明示同意，本平台不会将您的个人信息转让给任何第三方</strong>。<strong>涉及合并、收购、资产转让的，本平台将提前通知您并要求受让方继续以不低于本授权书的标准保护您的个人信息</strong>。</p>
<p><strong>5.4 公开披露承诺</strong></p>
<p><strong>未经您的单独明示同意，本平台不会公开披露您的个人信息</strong>。</p>
<p><strong>第六条 个人信息存储期限</strong></p>
<table>
<colgroup>
<col style="width: 33%" />
<col style="width: 33%" />
<col style="width: 33%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">信息类型</th>
<th style="text-align: center;">存储期限</th>
<th style="text-align: center;">期限届满后处理</th>
</tr>
</thead>
<tbody>
<tr>
<td>账户信息</td>
<td>账户存续期间</td>
<td>注销后30日内删除</td>
</tr>
<tr>
<td>录音文件（分析完成后）</td>
<td><strong>90日</strong></td>
<td>自动删除</td>
</tr>
<tr>
<td>实时识别结果</td>
<td>通话结束后<strong>90日</strong></td>
<td>自动删除</td>
</tr>
<tr>
<td>报告内容</td>
<td><strong>2年</strong></td>
<td>自动删除或匿名化</td>
</tr>
<tr>
<td>设备日志、操作日志</td>
<td><strong>2年</strong></td>
<td>自动删除</td>
</tr>
</tbody>
</table>
<p><strong>第七条 敏感个人信息处理单独同意</strong></p>
<p>依据《个人信息保护法》第28-29条，本平台处理您的敏感个人信息<strong>均需取得您的单独同意</strong>。本平台处理的敏感个人信息包括：</p>
<table>
<colgroup>
<col style="width: 33%" />
<col style="width: 33%" />
<col style="width: 33%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">敏感个人信息类型</th>
<th style="text-align: center;">涉及场景</th>
<th style="text-align: center;">单独同意</th>
</tr>
</thead>
<tbody>
<tr>
<td>生物识别信息（声纹）</td>
<td>实时识别功能</td>
<td>☐ 我同意</td>
</tr>
<tr>
<td>通话内容</td>
<td>录音分析、实时识别</td>
<td>☐ 我同意</td>
</tr>
<tr>
<td>行踪轨迹（可选）</td>
<td>用户主动提供</td>
<td>☐ 我同意</td>
</tr>
</tbody>
</table>
<blockquote>
<p><strong>【重要】</strong>
如您<strong>不同意</strong>本平台处理上述敏感个人信息，您将<strong>无法使用</strong>相应功能。但本平台<strong>不会</strong>因此拒绝为您提供其他不涉及敏感个人信息处理的服务。</p>
</blockquote>
<p><strong>处理敏感个人信息的必要性</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">敏感个人信息类型</th>
<th style="text-align: center;">处理的必要性</th>
</tr>
</thead>
<tbody>
<tr>
<td>声纹</td>
<td>用于在多人通话中区分催收方与债务人，提高识别准确度</td>
</tr>
<tr>
<td>通话内容</td>
<td>是违规识别的核心处理对象，无法通过其他方式替代</td>
</tr>
<tr>
<td>行踪轨迹</td>
<td>仅在您主动选择时使用，辅助判断催收行为发生地</td>
</tr>
</tbody>
</table>
<p><strong>第八条 您的权利</strong></p>
<p>依据《个人信息保护法》第44-50条，您对您的个人信息享有以下权利：</p>
<p><strong>8.1 知情权、决定权</strong></p>
<p>您有权<strong>了解</strong>本平台对您个人信息的处理情况，并<strong>自主决定</strong>是否同意。</p>
<p><strong>8.2 查阅权、复制权</strong></p>
<p>您有权<strong>查阅、复制</strong>您的个人信息。途径：APP 内"我的 -
个人信息"。</p>
<p><strong>8.3 更正权、补充权</strong></p>
<p>您有权<strong>要求更正、补充</strong>不准确或不完整的个人信息。</p>
<p><strong>8.4 删除权</strong></p>
<p>在下列情形之一发生时，您有权<strong>要求删除</strong>您的个人信息：</p>
<blockquote>
<p><strong>1.</strong> 处理目的已实现、无法实现或不再必要；</p>
<p><strong>2.</strong> 本平台停止提供产品或服务，或保存期限已届满；</p>
<p><strong>3.</strong> 您撤回同意；</p>
<p><strong>4.</strong> 本平台违反法律、行政法规或约定处理个人信息；</p>
<p><strong>5.</strong> 法律法规规定的其他情形。</p>
</blockquote>
<p><strong>8.5 撤回同意权</strong></p>
<p>您有权<strong>随时撤回同意</strong>。撤回同意的，<strong>不影响撤回前</strong>基于您的同意已经进行的个人信息处理活动的效力。</p>
<p>撤回方式：</p>
<blockquote>
<p><strong>•</strong> APP 内"设置 - 隐私设置"；</p>
<p><strong>•</strong> 客服渠道（联系方式见第12条）。</p>
</blockquote>
<p><strong>8.6 拒绝权</strong></p>
<p>您有权<strong>限制或拒绝</strong>本平台对您的个人信息进行处理。</p>
<p><strong>8.7 可携带权</strong></p>
<p>在符合条件时，您有权<strong>请求将您的个人信息转移</strong>至您指定的处理者。</p>
<p><strong>8.8 申请响应</strong></p>
<p>本平台将在<strong>收到您的申请之日起15个工作日内</strong>作出响应；情况复杂的可延长至<strong>30个工作日</strong>。</p>
<p><strong>8.9 投诉举报权</strong></p>
<p>您有权向本平台个人信息保护负责人投诉，或向网信、市场监管部门投诉，或向人民法院起诉。</p>
<p><strong>第九条 撤回授权</strong></p>
<p><strong>9.1 撤回方式</strong></p>
<p>您<strong>有权随时撤回</strong>本授权书项下的各项授权。撤回方式包括：</p>
<blockquote>
<p><strong>• APP 内撤回</strong>：设置 - 隐私设置 - 撤回授权；</p>
<p><strong>•
客服渠道</strong>：通过客服电话、在线客服、客服邮箱提出撤回请求。</p>
</blockquote>
<p><strong>9.2 撤回后果</strong></p>
<p>您撤回授权后：</p>
<blockquote>
<p><strong>1.</strong>
本平台将<strong>停止</strong>对您相关个人信息的处理（<strong>撤回前</strong>已合法进行的处理<strong>不受影响</strong>）；</p>
<p><strong>2.
相应功能将无法使用</strong>（如您撤回录音上传授权，您将无法使用"录音分析"功能）；</p>
<p><strong>3.
本平台不会</strong>因为您撤回授权而拒绝为您提供其他不涉及该授权的服务。</p>
</blockquote>
<p><strong>9.3 撤回与账户注销的区别</strong></p>
<blockquote>
<p><strong>•
撤回授权</strong>：仅撤回对特定信息的处理授权，<strong>账户保留</strong>；</p>
<p><strong>•
账户注销</strong>：注销后<strong>账户和全部个人信息均被删除</strong>（法定保留的除外）。</p>
</blockquote>
<p><strong>第十条 个人信息保护措施</strong></p>
<p>本平台采取<strong>以下措施</strong>保护您的个人信息安全：</p>
<blockquote>
<p><strong>1. 加密措施</strong>：使用 AES-256、TLS 1.2+
等行业标准加密；</p>
<p><strong>2. 访问控制</strong>：最小权限原则，双人复核机制；</p>
<p><strong>3. 物理安全</strong>：T3+ 等级以上专业数据中心；</p>
<p><strong>4. 网络安全</strong>：WAF、防火墙、入侵检测系统；</p>
<p><strong>5. 备份容灾</strong>：异地多活、定期备份；</p>
<p><strong>6. 人员管理</strong>：员工签订保密协议，定期培训；</p>
<p><strong>7.
合规审计</strong>：依据《个人信息保护法》第54条，定期开展合规审计。</p>
</blockquote>
<p><strong>第十一条 合规审计</strong></p>
<p>依据《个人信息保护法》第54条及《个人信息保护合规审计办法》（2025年5月1日施行），<strong>本平台定期开展个人信息保护合规审计</strong>：</p>
<table>
<colgroup>
<col style="width: 33%" />
<col style="width: 33%" />
<col style="width: 33%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">用户规模</th>
<th style="text-align: center;">审计频率</th>
<th style="text-align: center;">数据保护官（DPO）</th>
</tr>
</thead>
<tbody>
<tr>
<td>100万用户以上</td>
<td><strong>至少每2年</strong>1次</td>
<td>必须指定</td>
</tr>
<tr>
<td>10万-100万用户</td>
<td>至少每3年1次</td>
<td>必须指定</td>
</tr>
<tr>
<td>10万用户以下</td>
<td>按需开展</td>
<td>建议指定</td>
</tr>
</tbody>
</table>
<p><strong>审计内容</strong>包括：处理活动合法性基础、告知同意程序、数据最小化、留存期限、安全措施等。</p>
<p><strong>第十二条 联系我们</strong></p>
<p>如您对本授权书或您的个人信息处理有任何疑问、意见或建议，请通过以下方式联系我们：</p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">联系方式</th>
<th style="text-align: center;">详情</th>
</tr>
</thead>
<tbody>
<tr>
<td>客服电话</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服邮箱</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>在线客服</td>
<td>APP 内"我的 - 在线客服"</td>
</tr>
<tr>
<td>个人信息保护负责人</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>DPO 联系方式</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>办公地址</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>第十三条 附则</strong></p>
<p><strong>13.1 与隐私保护指引的关系</strong></p>
<p>本授权书与《还了吗隐私保护指引》共同构成本平台处理您个人信息的完整告知文件。<strong>两者不一致的，以更严格保护您权益的约定为准</strong>。</p>
<p><strong>13.2 法律适用与争议解决</strong></p>
<p>本授权书的订立、效力、解释、履行、修改及争议解决<strong>均适用中华人民共和国大陆地区法律</strong>。争议<strong>首先通过协商解决</strong>；协商不成的，提交<strong>运营方所在地有管辖权的人民法院</strong>通过诉讼方式解决。</p>
<p><strong>13.3 解释权</strong></p>
<p>本授权书的最终解释权<strong>归运营方所有</strong>。</p>
<p><strong>确认声明</strong></p>
<p>您通过本平台注册/登录页面勾选本文件，即表示您已充分阅读、理解并同意本文件全部条款；勾选本文件即视为同意本文件项下的各项条款，无需逐条勾选。</p>
<p>如本文件涉及敏感个人信息处理，本平台将另行通过独立勾选项取得您的单独同意。</p>
<p><strong>【个人信息收集与使用授权书正文至此结束】</strong></p>',1,3,NOW(3),NOW(3)),
('recording_auth','通话录音与语音识别授权协议','<p><strong>还了吗 通话录音与语音识别授权协议</strong></p>
<blockquote>
<p><strong>版本编号</strong>：V1.0</p>
<p>更新日期：2026年9月16日</p>
<p>生效日期：2026年9月16日</p>
<p><strong>文件性质</strong>：用户注册强制勾选文件（核心功能授权文件）</p>
<p><strong>法律依据</strong>：《个人信息保护法》第13、28、29条；《App收集使用个人信息最小必要评估规范·录音信息》（TTAF
077.10-2021）；《最高人民法院关于民事诉讼证据的若干规定》第68条；《刑事诉讼法》第50条</p>
</blockquote>
<p><strong>【核心风险告知】</strong></p>
<p><strong>本协议是您使用本平台</strong>"录音文件上传分析"<strong>及</strong>"实时通话识别"<strong>功能的核心法律文件。</strong>录音及语音识别涉及您本人、催收方及其他可能参与通话人员的敏感个人信息<strong>，本平台</strong>特别提示**：</p>
<blockquote>
<p><strong>1.
您应当确保</strong>：您上传或授权录制的录音<strong>合法取得</strong>，<strong>不构成窃听、偷录、非法监控</strong>；</p>
<p><strong>2.
您应当确保</strong>：您对录音中涉及的<strong>第三方个人信息</strong>有合法处理基础；</p>
<p><strong>3.
您应当知悉</strong>：录音中的催收方员工<strong>属于第三方</strong>，本平台对其个人信息的处理<strong>依赖您取得的合法基础</strong>；</p>
<p><strong>4.
本平台不为您的录音合法性承担责任</strong>——您违反本协议造成的全部法律责任<strong>由您本人承担</strong>。</p>
</blockquote>
<p><strong>【特别警示】</strong>
依据《刑法》第253条之一，<strong>非法获取、出售或提供公民个人信息，情节严重的，构成侵犯公民个人信息罪，最高可判处七年有期徒刑</strong>。本平台<strong>严禁</strong>任何违法获取、出售、传播通话录音的行为。</p>
<p><strong>第一条 定义</strong></p>
<p><strong>1.1 录音文件</strong></p>
<p>指您主动上传至本平台的、或经您授权由本平台录制的、您与第三方（含催收方）之间通话过程的音频文件。</p>
<p><strong>1.2 实时音频流</strong></p>
<p>指您启动本平台"实时通话识别"功能后，本平台<strong>实时接收并处理</strong>的通话音频流。本平台<strong>不永久存储</strong>实时音频流的原始数据。</p>
<p><strong>1.3 语音识别</strong></p>
<p>指本平台通过自动化技术（ASR，Automatic Speech
Recognition）将录音或实时音频流<strong>转换为文字</strong>的处理活动。</p>
<p><strong>1.4 违规情形识别</strong></p>
<p>指本平台依据现行有效的法律法规、监管规定、行业规范，<strong>自动比对</strong>识别录音中<strong>可能存在的违规情形</strong>（如暴力催收、威胁、侮辱、凌晨催收、对无关第三人催收等）。</p>
<p><strong>1.5 声纹</strong></p>
<p>指从您的语音中提取的、可用于识别说话人身份的生物识别特征。<strong>声纹属于敏感个人信息</strong>。</p>
<p><strong>1.6 催收方</strong></p>
<p>指与您发生催收业务往来的金融机构、消费金融公司、信用卡中心、银行、小额贷款公司、第三方催收机构等组织及其工作人员。</p>
<p><strong>第二条 您的核心承诺</strong></p>
<p><strong>2.1 录音来源合法</strong></p>
<p>您<strong>承诺并保证</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您上传至本平台的<strong>所有录音文件均为您本人通话过程中合法取得</strong>；</p>
<p><strong>2.</strong>
您取得录音的方式<strong>不违反</strong>《个人信息保护法》《刑法》《治安管理处罚法》等法律法规规定，<strong>不构成</strong>：</p>
<p><strong>•</strong> 窃听他人非公开的谈话、活动；</p>
<p><strong>•</strong> 偷录他人非同意的私人对话；</p>
<p><strong>•</strong> 非法监控他人通讯；</p>
<p><strong>•</strong> 偷拍、偷录他人隐私活动；</p>
<p><strong>3.</strong>
您取得录音的方式<strong>不侵犯</strong>他人合法权益，<strong>不违反</strong>您与他人之间的合同约定、保密义务、职业道德等。</p>
</blockquote>
<p><strong>2.2 录音场景真实</strong></p>
<p>您<strong>承诺并保证</strong>：</p>
<blockquote>
<p><strong>1.</strong>
录音的内容<strong>为您本人与催收方之间真实发生的通话</strong>；</p>
<p><strong>2.</strong>
录音<strong>不包含</strong>伪造、剪辑、拼接、变造的内容；</p>
<p><strong>3.</strong>
录音<strong>不包含</strong>您与催收方之外的、未经同意的第三方人员的通话内容。</p>
</blockquote>
<p><strong>2.3 录音用途合法</strong></p>
<p>您<strong>承诺并保证</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您将本平台基于录音出具的报告<strong>仅用于</strong>以下合法目的：</p>
<p><strong>•</strong> 您本人了解催收行为是否合规；</p>
<p><strong>•</strong>
您本人向金融监管部门进行<strong>基于真实事实</strong>的投诉或举报；</p>
<p><strong>•</strong>
您本人<strong>向人民法院、仲裁机构</strong>提交，作为<strong>证据使用</strong>（须依法举证）；</p>
<p><strong>•</strong> 您本人<strong>向律师</strong>咨询法律意见；</p>
<p><strong>2.</strong>
您<strong>不会</strong>将录音或报告用于以下任何目的：</p>
<p><strong>•</strong>
实施敲诈勒索、胁迫、恐吓、侮辱、诽谤等违法行为；</p>
<p><strong>•</strong>
参与、组织、教唆任何形式的"反催收"、"代理维权"、"债务优化"、"征信修复"等黑灰产活动；</p>
<p><strong>•</strong> 出售、转让、授权他人使用、用于商业宣传等。</p>
</blockquote>
<p><strong>2.4 录音中第三方信息处理合法基础</strong></p>
<p>您<strong>承诺并保证</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您对录音中涉及的<strong>催收方员工个人信息</strong>（声纹、通话内容、所属机构、岗位等）享有合法处理基础，包括但不限于：</p>
<p><strong>•</strong> 催收方员工在履行工作职责过程中主动与您沟通；</p>
<p><strong>•</strong> 您基于合法权益保护的必要而处理其公开履职信息；</p>
</blockquote>
<p><strong>•</strong>
您依据《个人信息保护法》第13条第1款第6项"实施新闻报道、舆论监督"等其他合法基础；</p>
<blockquote>
<p><strong>2.</strong>
您<strong>充分理解</strong>：依据《个人信息保护法》第13条，处理他人个人信息<strong>需要合法基础</strong>，本平台对您是否有合法基础<strong>不承担实质审查责任</strong>；</p>
<p><strong>3.</strong>
如您<strong>无法确认</strong>您对录音中涉及的第三方个人信息享有合法处理基础的，<strong>请勿上传</strong>该录音。</p>
</blockquote>
<p><strong>2.5 不上传禁止内容</strong></p>
<p>您<strong>承诺</strong>不会上传以下录音：</p>
<blockquote>
<p><strong>1.</strong> 涉及<strong>国家秘密</strong>的录音；</p>
<p><strong>2.</strong>
涉及<strong>他人隐私</strong>（非催收业务必要）的录音；</p>
<p><strong>3.</strong> 涉及<strong>他人商业秘密</strong>的录音；</p>
<p><strong>4. 偷录、窃听</strong>取得的录音；</p>
<p><strong>5. 伪造、剪辑、变造</strong>的录音；</p>
<p><strong>6.</strong>
涉及<strong>未成年人</strong>不当内容的录音（催收业务必要且符合未成年人保护规定的除外）；</p>
<p><strong>7.</strong>
涉及<strong>淫秽、暴力、恐怖</strong>等违法内容的录音。</p>
</blockquote>
<p><strong>第三条 本平台的处理活动</strong></p>
<p><strong>3.1 处理目的</strong></p>
<p>本平台<strong>仅在以下目的范围内</strong>处理您的录音：</p>
<blockquote>
<p><strong>1.</strong> 语音识别：将录音转换为文字；</p>
<p><strong>2.</strong>
违规情形识别：依据法律法规比对识别可能存在的违规情形；</p>
<p><strong>3.</strong> 报告生成：自动生成技术分析报告；</p>
</blockquote>
<p><strong>3.2 处理方式</strong></p>
<p>本平台对录音的处理方式<strong>包括</strong>：收集、存储、使用、删除、加密传输、加密存储、去标识化处理、自动化比对等。</p>
<p><strong>3.3 存储期限</strong></p>
<table>
<colgroup>
<col style="width: 33%" />
<col style="width: 33%" />
<col style="width: 33%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">录音/数据类型</th>
<th style="text-align: center;">存储期限</th>
<th style="text-align: center;">期限届满后处理</th>
</tr>
</thead>
<tbody>
<tr>
<td>录音文件（用户主动上传）</td>
<td>分析完成后<strong>90日</strong></td>
<td><strong>自动删除</strong></td>
</tr>
<tr>
<td>实时音频流（原始数据）</td>
<td><strong>不永久存储</strong></td>
<td>识别完成后立即销毁</td>
</tr>
<tr>
<td>语音识别结果（文本）</td>
<td>与报告一并存储 <strong>2年</strong></td>
<td>自动删除或匿名化</td>
</tr>
<tr>
<td>违规情形识别结果</td>
<td>与报告一并存储 <strong>2年</strong></td>
<td>自动删除或匿名化</td>
</tr>
<tr>
<td>技术分析报告</td>
<td><strong>2年</strong></td>
<td>自动删除或匿名化</td>
</tr>
</tbody>
</table>
<p><strong>3.4 存储地点</strong></p>
<p>您的录音<strong>均存储于中华人民共和国境内的服务器</strong>。</p>
<p><strong>3.5 加密措施</strong></p>
<p>本平台对录音的处理采取<strong>严格加密措施</strong>：</p>
<blockquote>
<p><strong>1. 传输加密</strong>：使用 TLS 1.2+ 加密传输；</p>
<p><strong>2. 存储加密</strong>：使用 AES-256 加密存储；</p>
<p><strong>3. 访问控制</strong>：仅授权人员因工作需要方可访问；</p>
<p><strong>4.
操作日志</strong>：所有访问、读取、删除操作<strong>留有日志</strong>。</p>
</blockquote>
<p><strong>第四条 敏感个人信息处理特别告知</strong></p>
<p><strong>4.1 声纹处理</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>处理目的</td>
<td>实时识别中区分催收方与债务人，提高识别准确度</td>
</tr>
<tr>
<td>处理方式</td>
<td>实时提取声纹特征，与预存特征比对</td>
</tr>
<tr>
<td>存储期限</td>
<td><strong>不存储</strong>实时声纹特征；仅在通话过程中实时使用</td>
</tr>
<tr>
<td>您的权利</td>
<td>您可拒绝提供，拒绝将无法使用"实时识别"功能，但"事后上传分析"功能不受影响</td>
</tr>
</tbody>
</table>
<p><strong>4.2 通话内容处理</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>处理目的</td>
<td>语音识别、违规情形识别、报告生成</td>
</tr>
<tr>
<td>处理方式</td>
<td>自动语音转文字、自动规则比对</td>
</tr>
<tr>
<td>存储期限</td>
<td>与报告一并存储2年</td>
</tr>
<tr>
<td>您的权利</td>
<td>您可要求删除；删除后无法再生成报告</td>
</tr>
</tbody>
</table>
<p><strong>4.3 第三方信息处理</strong></p>
<p>录音中<strong>催收方员工</strong>的个人信息（声纹、通话内容、所属机构、岗位等）的处理<strong>依赖您取得的合法基础</strong>。本平台采取以下措施保护：</p>
<blockquote>
<p><strong>1.
去标识化处理</strong>：报告中<strong>仅显示违规类型与所属机构大类</strong>，<strong>不显示员工姓名、具体电话号码、身份证号</strong>等可识别信息；</p>
<p><strong>2. 最小化原则</strong>：仅处理识别违规情形所必需的信息；</p>
<p><strong>3.
删除机制</strong>：录音文件分析完成后<strong>90日内自动删除</strong>。</p>
</blockquote>
<p><strong>第五条 您的权利</strong></p>
<p><strong>5.1 知情权</strong></p>
<p>您有权了解本平台对录音的处理活动、范围、方式、存储期限等。</p>
<p><strong>5.2 同意权与拒绝权</strong></p>
<p>您有权<strong>自主决定</strong>是否同意本平台处理您的录音。<strong>拒绝将无法使用相应功能</strong>。</p>
<p><strong>5.3 撤回权</strong></p>
<p>您有权<strong>随时撤回</strong>对本协议项下处理的同意。撤回后：</p>
<blockquote>
<p><strong>•</strong>
本平台将<strong>停止</strong>处理您的录音（撤回前已合法进行的处理不受影响）；</p>
<p><strong>• 相应功能将无法使用</strong>；</p>
<p><strong>• 已生成报告</strong>可由您自主决定是否删除。</p>
</blockquote>
<p>撤回方式：</p>
<blockquote>
<p><strong>•</strong> APP 内"设置 - 隐私设置 - 撤回授权"；</p>
<p><strong>•</strong> 客服渠道。</p>
</blockquote>
<p><strong>5.4 删除权</strong></p>
<p>您有权要求本平台<strong>删除</strong>录音及基于录音生成的报告、数据。删除后<strong>无法恢复</strong>。</p>
<p><strong>5.5 复制权</strong></p>
<p>您有权<strong>复制、下载</strong>您上传的录音及生成的报告。</p>
<p><strong>5.6 投诉举报权</strong></p>
<p>如您认为本平台对录音的处理违反法律法规，您有权向本平台个人信息保护负责人投诉，或向监管部门投诉，或向人民法院起诉。</p>
<p><strong>第六条 实时识别功能特别约定</strong></p>
<p><strong>6.1 实时识别功能说明</strong></p>
<p><strong>实时识别功能</strong>指您在接到催收电话时，启动本平台
APP，本平台<strong>实时</strong>识别通话内容，并在发现可能的违规情形时<strong>向您发出提示</strong>。</p>
<p><strong>6.2 实时识别的技术实现</strong></p>
<p>实时识别功能通过以下技术实现：</p>
<blockquote>
<p><strong>1.
音频流传输</strong>：您手机麦克风采集的音频<strong>加密传输</strong>至本平台服务器；</p>
<p><strong>2. 实时语音识别</strong>：服务器端实时进行 ASR 处理；</p>
<p><strong>3. 实时规则比对</strong>：实时与违规情形规则库进行比对；</p>
<p><strong>4.
实时提示</strong>：识别到可能的违规情形时，向您手机端推送提示信息。</p>
</blockquote>
<p><strong>6.3 实时识别不存储原始音频</strong></p>
<p>本平台<strong>严格承诺</strong>：<strong>实时识别功能不永久存储原始音频流数据</strong>。实时音频流仅在通话过程中<strong>实时处理</strong>，通话结束后<strong>原始音频立即销毁</strong>，仅保留<strong>识别结果文本</strong>。</p>
<p><strong>6.4 实时识别的特殊告知</strong></p>
<p>您<strong>特别知悉并同意</strong>：</p>
<blockquote>
<p><strong>1.</strong>
实时识别功能依赖您手机的<strong>麦克风权限</strong>和<strong>电话权限</strong>；</p>
<p><strong>2.</strong>
实时识别过程中，本平台<strong>可能</strong>在后台运行相关进程；</p>
<p><strong>3.</strong>
实时识别可能因<strong>网络、设备、系统</strong>等原因出现<strong>延迟、中断、识别错误</strong>等情形；</p>
<p><strong>4.
您不应</strong>将实时识别结果作为<strong>唯一依据</strong>作出重大决定；</p>
<p><strong>5.</strong>
实时识别过程中可能涉及<strong>通话对方（催收方员工）的个人信息</strong>处理，您应当<strong>确保</strong>对相关人员有合法处理基础。</p>
</blockquote>
<p><strong>6.5 通话对方知情同意风险提示</strong></p>
<p><strong>【重要风险提示】</strong> 您<strong>充分知悉</strong>：</p>
<blockquote>
<p><strong>1.</strong>
实时识别功能在您与催收方通话过程中，<strong>未经催收方明确同意</strong>对其通话内容进行实时识别与处理；</p>
<p><strong>2.</strong>
本平台<strong>依赖您</strong>对催收方个人信息的合法处理基础，<strong>不对此进行实质审查</strong>；</p>
<p><strong>3.
如您对催收方无合法处理基础</strong>，您的行为可能构成对催收方个人信息权益的侵害，您应当<strong>承担全部法律责任</strong>；</p>
<p><strong>4.</strong>
如您<strong>对催收方无合法处理基础</strong>，<strong>请勿使用实时识别功能</strong>，可使用事后上传分析功能（事后场景中，催收方已主动与您沟通，您对公开履职信息有合理处理基础）。</p>
</blockquote>
<p><strong>第七条 证据使用特别约定</strong></p>
<p><strong>7.1 录音作为证据的合法性</strong></p>
<p>依据《最高人民法院关于民事诉讼证据的若干规定》第68条、《刑事诉讼法》第50条等规定，<strong>录音</strong>可以作为证据使用，但<strong>应当同时满足</strong>：</p>
<blockquote>
<p><strong>1.
不侵犯他人合法权益</strong>（如偷录、窃听取得的录音不得作为证据）；</p>
<p><strong>2.
不违反法律禁止性规定</strong>（如不得通过非法手段获取）；</p>
<p><strong>3. 不违背公序良俗</strong>。</p>
</blockquote>
<p><strong>7.2 您的承诺</strong></p>
<p>您<strong>承诺</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您上传至本平台的录音<strong>符合</strong>上述合法性要求；</p>
<p><strong>2.</strong>
您将录音作为证据使用时，<strong>保证录音的真实性、完整性、原始性</strong>，<strong>不剪辑、不拼接、不变造</strong>；</p>
<p><strong>3.</strong>
如您对录音的真实性、合法性<strong>有任何隐瞒</strong>，<strong>由您承担全部法律责任</strong>。</p>
</blockquote>
<p><strong>7.3 本平台不提供证据效力保证</strong></p>
<p><strong>【重要免责】</strong>
本平台<strong>不对</strong>录音的证据效力作任何保证：</p>
<blockquote>
<p><strong>1.</strong>
录音是否被法院、仲裁机构采信，<strong>由司法机关依法决定</strong>；</p>
<p><strong>2.</strong>
本平台<strong>不出具</strong>录音"真实性、合法性"的法律证明；</p>
<p><strong>3.</strong>
如您需要<strong>具有法律效力的录音证据</strong>，建议<strong>通过公证处、司法鉴定中心、第三方存证机构</strong>等办理证据保全。</p>
</blockquote>
<p><strong>7.4 推荐证据保全方式</strong></p>
<p>为提升录音的证据效力，本平台<strong>强烈建议</strong>您：</p>
<blockquote>
<p><strong>1. 委托公证处对录音进行公证；</strong></p>
<p><strong>2. 委托司法鉴定中心对录音真实性进行鉴定；</strong></p>
<p><strong>3.
保存通话记录、短信、其他关联证据，形成完整证据链。</strong></p>
</blockquote>
<p><strong>第八条 违规处理</strong></p>
<p><strong>8.1 您的违规情形</strong></p>
<p>发生下列情形之一的，构成本协议项下的<strong>重大违约</strong>：</p>
<blockquote>
<p><strong>1.</strong>
您上传<strong>非法取得、伪造、变造、剪辑</strong>的录音；</p>
<p><strong>2.</strong>
您将录音或报告用于<strong>违法犯罪活动</strong>；</p>
<p><strong>3.</strong>
您将录音或报告用于<strong>"反催收"等黑灰产活动</strong>；</p>
<p><strong>4.</strong>
您对录音中涉及的第三方个人信息<strong>无合法处理基础</strong>导致他人合法权益受损；</p>
<p><strong>5.</strong> 您违反本协议第二条"您的核心承诺"的任一条款。</p>
</blockquote>
<p><strong>8.2 本平台的权利</strong></p>
<p>发生上述违规情形的，本平台<strong>有权</strong>：</p>
<blockquote>
<p><strong>1. 立即删除</strong>相关录音、报告、数据；</p>
<p><strong>2. 暂停或终止</strong>您的账户及服务；</p>
<p><strong>3. 依法向公安机关、监管部门举报</strong>并配合调查；</p>
<p><strong>4.</strong> 依法向您<strong>主张赔偿责任</strong>。</p>
</blockquote>
<p><strong>第九条 免责条款</strong></p>
<p><strong>9.1 本平台免责情形</strong></p>
<p>在适用法律允许的最大范围内，本平台<strong>不对以下情况承担任何责任</strong>：</p>
<blockquote>
<p><strong>1.</strong>
因<strong>您违反本协议第二条核心承诺</strong>导致的任何法律后果；</p>
<p><strong>2.</strong>
因<strong>您对录音中第三方个人信息无合法处理基础</strong>导致的任何法律后果；</p>
<p><strong>3.</strong>
因<strong>您将录音或报告用于非法目的</strong>导致的任何法律后果；</p>
<p><strong>4.</strong>
因<strong>不可抗力</strong>（包括但不限于自然灾害、网络攻击、政策法规重大变化等）导致的服务中断或异常；</p>
<p><strong>5.</strong>
因<strong>第三方原因</strong>（包括但不限于电信运营商、设备问题、不可归责于本平台的事件等）导致的服务异常；</p>
<p><strong>6.</strong>
因<strong>录音识别错误、识别遗漏、识别延迟</strong>等导致的任何损失（语音识别准确率受技术限制，本平台<strong>不保证</strong>100%准确）。</p>
</blockquote>
<p><strong>9.2 责任承担</strong></p>
<p><strong>您理解并同意</strong>：<strong>因您违反本协议导致的任何法律纠纷、行政处罚、刑事追诉、民事赔偿，均由您本人承担全部责任，与本平台无关</strong>。如本平台因此遭受任何损失（包括但不限于行政处罚、民事赔偿、声誉损失、维权费用等），本平台<strong>有权向您追偿</strong>。</p>
<p><strong>第十条 协议解除与终止</strong></p>
<p><strong>10.1 您的解除权</strong></p>
<p>您<strong>有权</strong>随时停止使用本平台录音及语音识别功能，撤回同意、删除录音、注销账户。本协议自您撤回同意或注销账户之时起终止。</p>
<p><strong>10.2 本平台的终止权</strong></p>
<p>发生本协议第8.1条任一情形的，本平台<strong>有权</strong>立即终止本协议，<strong>停止</strong>为您提供本协议项下服务。</p>
<p><strong>10.3 协议终止后的处理</strong></p>
<p>本协议终止后：</p>
<blockquote>
<p><strong>1.</strong>
本平台<strong>停止</strong>为您提供录音及语音识别服务；</p>
<p><strong>2.</strong>
本平台将依据《还了吗隐私保护指引》对您的录音、报告、数据<strong>进行删除或匿名化处理</strong>（法律法规另有规定的除外）；</p>
<p><strong>3.
不影响</strong>本协议中依法应当继续有效的条款（包括但不限于违约责任条款、争议解决条款、免责条款等）的效力。</p>
</blockquote>
<p><strong>第十一条 法律适用与争议解决</strong></p>
<p><strong>11.1 法律适用</strong></p>
<p>本协议的订立、效力、解释、履行、修改及争议解决<strong>均适用中华人民共和国大陆地区法律</strong>（不含香港、澳门、台湾地区法律）。</p>
<p><strong>11.2 争议解决</strong></p>
<p>本协议项下发生的任何争议，双方<strong>应首先友好协商解决</strong>。协商不成的，任何一方均<strong>同意</strong>将争议提交<strong>运营方所在地有管辖权的人民法院</strong>通过诉讼方式解决。</p>
<p><strong>第十二条 其他</strong></p>
<p><strong>12.1 完整协议</strong></p>
<p>本协议与《还了吗软件许可及服务协议》《还了吗隐私保护指引》《还了吗个人信息收集与使用授权书》共同构成本平台与您就本协议标的达成的<strong>完整法律文件</strong>。</p>
<p><strong>12.2 可分割性</strong></p>
<p>如本协议任一条款被有管辖权的人民法院认定为<strong>无效</strong>或<strong>不可执行</strong>，不影响其他条款的效力。</p>
<p><strong>12.3 解释权</strong></p>
<p>本协议的最终解释权<strong>归运营方所有</strong>。</p>
<p><strong>第十三条 运营方信息</strong></p>
<table>
<colgroup>
<col style="width: 50%" />
<col style="width: 50%" />
</colgroup>
<thead>
<tr>
<th style="text-align: center;">项目</th>
<th style="text-align: center;">内容</th>
</tr>
</thead>
<tbody>
<tr>
<td>运营主体名称</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>统一社会信用代码</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服电话</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>客服邮箱</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>个人信息保护负责人</td>
<td>【待运营方填写】</td>
</tr>
<tr>
<td>DPO 联系方式</td>
<td>【待运营方填写】</td>
</tr>
</tbody>
</table>
<p><strong>确认声明</strong></p>
<p>您通过本平台注册/登录页面勾选本文件，即表示您已充分阅读、理解并同意本文件全部条款；勾选本文件即视为同意本文件项下的各项条款，无需逐条勾选。</p>
<p>如本文件涉及敏感个人信息处理，本平台将另行通过独立勾选项取得您的单独同意。</p>
<p><strong>【通话录音与语音识别授权协议正文至此结束】</strong></p>',1,4,NOW(3),NOW(3));

INSERT IGNORE INTO schema_migrations (version, name)
VALUES ('S19', '用户协议：agreements + agreement_consents + 4 份 V1.0 种子');

SELECT
    (SELECT COUNT(*) FROM agreements) AS agreement_rows,
    (SELECT COUNT(*) FROM agreement_consents) AS consent_rows;
