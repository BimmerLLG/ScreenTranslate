package com.screentranslate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentranslate.ui.theme.ScreenTranslateTheme

class TestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenTranslateTheme {
                TestScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR 测试文本") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("English") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("한국어") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("中文") })
            }
            when (tab) {
                0 -> TextContent(text = englishText, label = "English / 英语")
                1 -> TextContent(text = koreanText, label = "한국어 / 韩语")
                2 -> TextContent(text = chineseText, label = "中文 / Chinese")
            }
        }
    }
}

@Composable
private fun TextContent(text: String, label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
            )
        }
    }
}

private val englishText = """
The Industrial Revolution, which began in Britain in the late 18th century, fundamentally transformed the way goods were produced and how people lived and worked. Before this period, most manufacturing was done in homes or small workshops using hand tools or basic machinery. The invention of the steam engine by James Watt in 1769 provided a reliable source of power that could be used to drive machinery, leading to the establishment of large factories. These factories concentrated production in urban areas, drawing people from rural communities in search of employment.

The textile industry was at the forefront of this transformation. Innovations such as the spinning jenny, the water frame, and the power loom mechanized the production of cloth, dramatically increasing output while reducing costs. As factories grew, so did the demand for coal and iron, fueling advances in mining and metallurgy. The development of railways and steamships revolutionized transportation, enabling goods and raw materials to be moved quickly and efficiently over long distances.

However, the Industrial Revolution also brought significant social challenges. Working conditions in factories were often harsh, with long hours, low wages, and dangerous machinery. Child labor was widespread, and urban overcrowding led to poor sanitation and the spread of disease. In response, labor movements began to organize, demanding better working conditions, fair wages, and the right to vote. Over time, these efforts led to the establishment of labor laws, trade unions, and social welfare programs.

The impact of the Industrial Revolution extended beyond economics and politics. It reshaped the social structure, giving rise to a new middle class of industrialists and professionals while also creating a large urban working class. It influenced art, literature, and philosophy, with movements such as Romanticism reacting against the perceived dehumanization of industrial society. The legacy of the Industrial Revolution continues to shape our world today, from the technologies we use to the ways we organize our economies and societies.

In the 21st century, we are experiencing a new wave of technological change often called the Fourth Industrial Revolution. This era is characterized by advances in artificial intelligence, robotics, the Internet of Things, and biotechnology. Just as the first Industrial Revolution transformed agriculture and manufacturing, this new revolution is reshaping how we work, communicate, and live. It raises questions about privacy, employment, and inequality that echo the debates of two centuries ago. Understanding the history of the Industrial Revolution can help us navigate the challenges and opportunities of our own time.

Computer science is the study of computation, information processing, and the design of computer systems. It encompasses both theoretical foundations, such as algorithms and data structures, and practical applications, including software engineering, artificial intelligence, and cybersecurity. One of the fundamental concepts in computer science is the algorithm: a step-by-step procedure for solving a problem or accomplishing a task. Algorithms are the building blocks of all software, from simple calculators to complex machine learning models.

The field of artificial intelligence (AI) has experienced remarkable progress in recent years. Machine learning, a subset of AI, involves training models on large datasets to recognize patterns and make predictions. Deep learning, a further subset, uses neural networks with many layers to process complex data such as images, speech, and text. Modern large language models can generate human-quality text, translate between languages, write code, and even engage in creative writing. These models are trained on vast amounts of text data scraped from the internet, books, and other sources.

Despite their impressive capabilities, AI systems face significant limitations. They can perpetuate biases present in their training data, produce incorrect or harmful outputs, and lack true understanding or consciousness. Researchers are actively working on improving the safety, fairness, and reliability of AI systems. Techniques such as reinforcement learning from human feedback, constitutional AI, and adversarial testing are being developed to align AI behavior with human values.

The ethics of artificial intelligence is a growing area of concern. Questions about privacy, surveillance, job displacement, and the concentration of power in a few technology companies are at the forefront of public debate. As AI becomes more integrated into our daily lives, from recommendation algorithms to autonomous vehicles, it is crucial to develop regulatory frameworks that ensure these technologies are used responsibly and for the benefit of all.

Software engineering is the discipline of designing, building, and maintaining software systems. It applies engineering principles to the development process, emphasizing reliability, efficiency, and maintainability. Modern software development often follows agile methodologies, which prioritize iterative development, collaboration, and responsiveness to change. Version control systems like Git enable teams to manage code changes and collaborate effectively. Testing is an integral part of software engineering, with practices such as unit testing, integration testing, and continuous integration helping to ensure code quality.
""".trimIndent()

private val koreanText = """
한국은 동아시아에 위치한 나라로, 풍부한 역사와 독특한 문화를 가지고 있습니다. 한반도의 남쪽에 자리 잡고 있으며, 북쪽으로는 북한과 국경을 접하고 있습니다. 한국의 수도는 서울로, 인구 약 천만 명이 거주하는 세계적인 대도시입니다. 한국은 경제 발전과 민주화의 모범 사례로 꼽히며, 짧은 기간 동안 놀라운 성장을 이루었습니다.

한국어는 한국의 공식 언어이며, 전 세계적으로 약 8천만 명이 사용하고 있습니다. 한글은 15세기 세종대왕과 학자들이 창제한 문자 체계로, 과학적이고 체계적인 문자로 인정받고 있습니다. 한글은 자음과 모음의 조합으로 이루어져 있으며, 초성, 중성, 종성으로 구성된 음절 단위로 표기됩니다. 한글날은 10월 9일로, 한글 창제를 기념하는 국경일입니다.

한국 요리는 전 세계적으로 인기가 높습니다. 김치, 불고기, 비빔밥, 떡볶이, 삼겹살 등 다양한 요리가 있으며, 각 지역마다 특색 있는 음식이 발달했습니다. 김치는 한국의 대표적인 발효 식품으로, 배추와 각종 양념을 발효시켜 만듭니다. 비빔밥은 밥 위에 다양한 나물과 고기, 계란 등을 얹고 고추장과 함께 비벼 먹는 요리입니다. 한국 식사 문화에서는 여러 사람이 함께 음식을 나누어 먹는 것을 중요하게 여깁니다.

한국의 현대 문화는 K-pop, K-드라마, K-영화 등을 통해 전 세계에 널리 알려져 있습니다. BTS, 블랙핑크, 싸이 등의 K-pop 아티스트들은 세계적인 인기를 누리고 있으며, '기생충', '오징어 게임' 등의 한국 콘텐츠는 아카데미 상과 에미 상을 수상하며 그 작품성을 인정받았습니다. 한국의 뷰티 산업과 패션 또한 세계적인 주목을 받고 있으며, 한국 화장품은 우수한 품질과 합리적인 가격으로 많은 사랑을 받고 있습니다.

한국의 전통 문화도 매우 풍부합니다. 한복은 한국의 전통 의상으로, 아름다운 색감과 우아한 실루엣이 특징입니다. 명절이나 특별한 날에 한복을 입는 전통이 이어지고 있습니다. 한국의 전통 음악인 국악은 다양한 악기와 독특한 리듬으로 구성되어 있으며, 판소리, 민요, 정악 등 여러 장르로 나뉩니다. 한국의 전통 춤인 탈춤과 강강술래는 유네스코 인류무형문화유산으로 등재되어 있습니다.

한국의 교육 열기는 세계적으로 유명합니다. 한국 학생들은 학업 성취도가 높으며, 특히 수학과 과학 분야에서 뛰어난 성과를 보이고 있습니다. 한국의 대학 입시 제도는 매우 경쟁이 치열하며, 많은 학생들이 좋은 대학에 진학하기 위해 오랜 시간 공부합니다. 최근에는 창의성과 인성 교육의 중요성이 강조되고 있으며, 교육 시스템도 점차 변화하고 있습니다.

한국은 기술 강국으로도 유명합니다. 삼성, LG, 현대, SK 등의 글로벌 기업은 전자제품, 반도체, 자동차, 조선 등 다양한 분야에서 세계 시장을 선도하고 있습니다. 한국은 세계에서 인터넷 속도가 가장 빠른 나라 중 하나이며, 5G 통신을 세계 최초로 상용화했습니다. 한국의 스타트업 생태계도 빠르게 성장하고 있으며, 혁신적인 기술과 아이디어를 가진 젊은 기업가들이 많이 활동하고 있습니다.

한국의 자연 경관도 매우 아름답습니다. 설악산, 한라산, 지리산 등의 명산과 제주도, 남해안, 동해안의 아름다운 해안선은 많은 관광객을 끌어들입니다. 봄에는 벚꽃이 만발하고, 가을에는 단풍이 물드는 풍경이 장관을 이룹니다. 한국의 사계절은 뚜렷한 특징을 가지고 있으며, 각 계절마다 다양한 축제와 행사가 열립니다. 한국을 방문하는 외국인 관광객은 매년 증가하고 있으며, 한국은 세계적으로 인기 있는 여행지로 자리 잡고 있습니다.

인공지능 기술의 발전은 우리 사회에 큰 변화를 가져오고 있습니다. 머신러닝과 딥러닝 기술은 의료, 금융, 교육, 교통 등 다양한 분야에서 혁신을 이끌고 있습니다. 특히 자연어 처리 기술의 발전으로 기계 번역, 음성 인식, 챗봇 등의 서비스가 크게 향상되었습니다. 한국 정부도 인공지능 기술 발전을 위한 정책을 적극적으로 추진하고 있으며, 많은 연구 기관과 기업이 인공지능 연구에 투자하고 있습니다.

소프트웨어 개발은 현대 기술 산업의 핵심입니다. 개발자들은 다양한 프로그래밍 언어와 도구를 사용하여 애플리케이션과 시스템을 구축합니다. 한국의 소프트웨어 산업도 빠르게 성장하고 있으며, 많은 우수한 개발자들이 활동하고 있습니다. 오픈 소스 소프트웨어의 중요성도 점점 커지고 있으며, 한국 개발자들도 다양한 오픈 소스 프로젝트에 기여하고 있습니다. 클라우드 컴퓨팅, 빅데이터, 사물인터넷 등의 기술이 융합되면서 새로운 서비스와 비즈니스 모델이 계속해서 등장하고 있습니다.
""".trimIndent()

private val chineseText = """
人工智能技术的飞速发展正在深刻地改变着我们的生活方式和社会结构。从智能手机上的语音助手到自动驾驶汽车，从医疗影像诊断到金融风险预测，人工智能已经渗透到我们生活的方方面面。深度学习作为人工智能的核心技术之一，通过模拟人类大脑的神经网络结构，使计算机能够从海量数据中学习规律和模式。

自然语言处理是人工智能领域中最具挑战性的方向之一。让计算机理解和生成人类语言涉及到语音识别、语义理解、机器翻译等多个子任务。近年来，基于变换器架构的大规模语言模型取得了突破性进展，能够完成文本生成、代码编写、对话交互等复杂任务。

中国在人工智能领域的投入和发展速度令世界瞩目。无论是在学术研究还是产业应用方面，中国都展现出了强大的竞争力。北京的百度、深圳的腾讯、杭州的阿里巴巴等科技巨头都在人工智能研发上投入了大量资源。同时，众多创新型初创企业也在计算机视觉、语音识别、智能机器人等细分领域取得了显著成就。

汉字作为世界上最古老的文字系统之一，具有独特的结构和丰富的内涵。每个汉字都承载着深厚的文化底蕴和历史记忆。从甲骨文到金文，从篆书到楷书，汉字的演变过程本身就是一部中华文明的发展史。现代汉字由笔画、部首和结构组成，总数超过八万个，其中常用汉字约三千五百个。

中国的传统文化博大精深，诗词歌赋是中华文化的瑰宝。唐代诗人李白被誉为诗仙，他的诗句豪放飘逸，充满浪漫主义色彩。杜甫则被称为诗圣，他的作品关注民生疾苦，具有深刻的现实主义精神。宋代词人苏轼的作品融会贯通，既有豪放雄浑的气魄，又有细腻婉约的情感。

随着移动互联网和数字技术的普及，中文的数字化处理能力不断提升。手写识别、语音输入、光学字符识别等技术使得中文信息的获取和传递更加便捷高效。在光学字符识别领域，对于中文这种字符集庞大、字形复杂、字体多样的文字系统，深度学习模型展现出了超越传统方法的识别精度。
""".trimIndent()
