package bb.pix.wall.discovery

import bb.pix.wall.discovery.model.CategoryGroup
import bb.pix.wall.discovery.model.WallpaperCategory

object CategoryCatalog {

    val groups: List<CategoryGroup> =
        listOf(
            CategoryGroup("premium", "Premium Mix", "Best-looking cross-category wallpaper pool"),
            CategoryGroup("amoled", "AMOLED & Dark", "OLED-friendly dark and cinematic styles"),
            CategoryGroup("hero", "Super Hero & Fiction", "Heroes, villains, comics and fantasy"),
            CategoryGroup("military", "Army & Military", "Military, tactical and defence themes"),
            CategoryGroup("country", "Countries", "Countries, flags, landmarks and national identity"),
            CategoryGroup("culture", "Culture & Traditional", "Heritage, festivals and traditional aesthetics"),
            CategoryGroup("people", "People & Photography", "Portrait, DSLR, selfie and cinematic photography"),
            CategoryGroup("technology", "Technology", "Computers, coding, cyber and futuristic tech"),
            CategoryGroup("apple", "Apple & iOS", "iPhone, iOS, Mac and Apple-inspired aesthetics"),
            CategoryGroup("machine", "Machines & Engineering", "Mechanical, industrial and engineering subjects"),
            CategoryGroup("vehicles", "Cars & Vehicles", "Cars, bikes, aircraft and motorsport"),
            CategoryGroup("nature", "Nature", "Mountains, forests, oceans and landscapes"),
            CategoryGroup("space", "Space", "Galaxy, planets, stars and astronomy"),
            CategoryGroup("sky", "Sky & Weather", "Clouds, aurora, storms and sunsets"),
            CategoryGroup("animals", "Animals", "Wildlife, pets and birds"),
            CategoryGroup("plants", "Flowers & Plants", "Flowers, botanical and greenery"),
            CategoryGroup("architecture", "Architecture", "Buildings, interiors and structural design"),
            CategoryGroup("cities", "Cities & Travel", "Cities, streets, landmarks and destinations"),
            CategoryGroup("art", "Art", "Digital, abstract, concept and AI-inspired art"),
            CategoryGroup("minimal", "Minimal", "Clean, geometric and gradient wallpapers"),
            CategoryGroup("gaming", "Gaming", "Gaming worlds, setups and genres"),
            CategoryGroup("anime", "Anime & Manga", "Anime, manga and Japanese illustration"),
            CategoryGroup("quotes", "Quotes & Typography", "Typography, calligraphy and motivation"),
            CategoryGroup("mood", "Mood", "Calm, moody, powerful and emotional aesthetics"),
            CategoryGroup("color", "Colors", "Color-focused wallpaper discovery"),
            CategoryGroup("trending", "Trending", "Popular, fresh and discovery-first content"),
            CategoryGroup("seasonal", "Seasonal & Festivals", "Festival and occasion wallpapers"),
            CategoryGroup("professional", "Professional", "Business, workspace and productivity"),
            CategoryGroup("science", "Science", "Physics, astronomy, biology and mathematics"),
            CategoryGroup("sports", "Sports", "Cricket, football, motorsport and fitness"),
            CategoryGroup("food", "Food & Drinks", "Food photography, coffee and desserts"),
            CategoryGroup("luxury", "Luxury", "Premium cars, watches, interiors and gold"),
            CategoryGroup("spiritual", "Spiritual", "Meditation, sacred art and peaceful themes"),
        )

    private fun c(
        id: String,
        title: String,
        group: String,
        vararg terms: String,
        aliases: List<String> = emptyList(),
        colors: List<String> = emptyList(),
        weight: Float = 1.0f,
    ) = WallpaperCategory(
        id = id,
        title = title,
        groupId = group,
        searchTerms = terms.toList(),
        aliases = aliases,
        preferredColors = colors,
        premiumWeight = weight,
    )

    val builtIn: List<WallpaperCategory> =
        listOf(
            // Premium / AMOLED
            c("premium_cinematic", "Cinematic", "premium", "cinematic wallpaper", "cinematic photography", weight = 1.35f),
            c("premium_colorful", "Premium Colorful", "premium", "colorful wallpaper", "vibrant abstract", weight = 1.25f),
            c("amoled", "AMOLED", "amoled", "amoled wallpaper", colors = listOf("000000"), weight = 1.4f),
            c("pure_black", "Pure Black", "amoled", "pure black wallpaper", colors = listOf("000000"), weight = 1.4f),
            c("deep_black", "Deep Black", "amoled", "deep black wallpaper", colors = listOf("000000"), weight = 1.35f),
            c("black_white", "Black & White", "amoled", "black and white wallpaper", "monochrome photography", weight = 1.15f),
            c("dark_minimal", "Dark Minimal", "amoled", "dark minimal wallpaper", weight = 1.25f),
            c("dark_cinematic", "Dark Cinematic", "amoled", "dark cinematic wallpaper", weight = 1.25f),
            c("neon_dark", "Neon Dark", "amoled", "dark neon wallpaper", "neon cyberpunk"),
            c("cyberpunk", "Cyberpunk", "amoled", "cyberpunk wallpaper", "neon city"),

            // Hero / fiction
            c("superhero", "Super Hero", "hero", "superhero wallpaper", "comic hero"),
            c("comic_art", "Comic Art", "hero", "comic art wallpaper"),
            c("villain", "Villain", "hero", "villain wallpaper"),
            c("fantasy", "Fantasy", "hero", "fantasy wallpaper"),
            c("scifi", "Sci-Fi", "hero", "science fiction wallpaper"),
            c("samurai", "Samurai", "hero", "samurai wallpaper"),
            c("ninja", "Ninja", "hero", "ninja wallpaper"),

            // Military
            c("army", "Army", "military", "army wallpaper", "military soldier"),
            c("special_forces", "Special Forces", "military", "special forces wallpaper"),
            c("navy", "Navy", "military", "navy military wallpaper"),
            c("air_force", "Air Force", "military", "air force wallpaper"),
            c("fighter_jet", "Fighter Jet", "military", "fighter jet wallpaper"),
            c("military_helicopter", "Military Helicopter", "military", "military helicopter wallpaper"),
            c("tank", "Tank", "military", "tank military wallpaper"),
            c("tactical", "Tactical", "military", "tactical military wallpaper"),

            // Culture
            c("culture_world", "World Culture", "culture", "world culture wallpaper"),
            c("traditional", "Traditional", "culture", "traditional culture wallpaper"),
            c("indian_culture", "Indian Culture", "culture", "indian culture wallpaper"),
            c("japanese_culture", "Japanese Culture", "culture", "japanese traditional wallpaper"),
            c("arabic_culture", "Arabic Culture", "culture", "arabic culture wallpaper"),
            c("african_culture", "African Culture", "culture", "african culture wallpaper"),
            c("folk_art", "Folk Art", "culture", "folk art wallpaper"),
            c("heritage", "Heritage", "culture", "heritage architecture wallpaper"),
            c("traditional_dress", "Traditional Dress", "culture", "traditional clothing photography"),

            // People / photography
            c("portrait", "Portrait", "people", "portrait photography wallpaper"),
            c("selfie", "Selfie", "people", "selfie photography"),
            c("dslr", "DSLR Photography", "people", "dslr photography wallpaper"),
            c("cinematic_portrait", "Cinematic Portrait", "people", "cinematic portrait photography", weight = 1.2f),
            c("studio_portrait", "Studio Portrait", "people", "studio portrait photography"),
            c("street_portrait", "Street Portrait", "people", "street portrait photography"),
            c("fashion", "Fashion", "people", "fashion photography wallpaper"),
            c("silhouette", "Silhouette", "people", "silhouette photography"),
            c("bokeh", "Bokeh", "people", "bokeh photography wallpaper"),

            // Technology
            c("technology", "Technology", "technology", "technology wallpaper"),
            c("technical", "Technical", "technology", "technical technology wallpaper"),
            c("laptop", "Laptop", "technology", "laptop wallpaper"),
            c("computer", "Computer / PC", "technology", "computer pc wallpaper"),
            c("coding", "Coding", "technology", "coding programming wallpaper"),
            c("programming", "Programming", "technology", "programming code wallpaper"),
            c("terminal", "Terminal", "technology", "terminal command line wallpaper"),
            c("termux", "Termux", "technology", "android terminal wallpaper", "termux"),
            c("hacking", "Hacking", "technology", "hacker cyber security wallpaper"),
            c("anonymous", "Anonymous", "technology", "anonymous hacker wallpaper"),
            c("cybersecurity", "Cyber Security", "technology", "cyber security wallpaper"),
            c("linux", "Linux", "technology", "linux wallpaper"),
            c("android", "Android", "technology", "android technology wallpaper"),
            c("ai", "Artificial Intelligence", "technology", "artificial intelligence wallpaper"),
            c("robotics", "Robotics", "technology", "robotics technology wallpaper"),
            c("server", "Server", "technology", "server rack wallpaper"),
            c("datacenter", "Data Center", "technology", "data center wallpaper"),
            c("circuit", "Circuit Board", "technology", "circuit board wallpaper"),
            c("processor", "CPU / Chip", "technology", "processor chip technology wallpaper"),
            c("gaming_setup", "Gaming Setup", "technology", "gaming pc setup wallpaper"),

            // Apple
            c("ios", "iOS", "apple", "ios wallpaper", "apple ios gradient", weight = 1.2f),
            c("iphone", "iPhone", "apple", "iphone wallpaper", weight = 1.2f),
            c("iphone_stock", "iPhone Stock Style", "apple", "iphone stock wallpaper", "ios stock gradient"),
            c("apple", "Apple", "apple", "apple wallpaper"),
            c("macbook", "MacBook", "apple", "macbook wallpaper"),
            c("macos", "macOS", "apple", "macos wallpaper"),
            c("apple_watch", "Apple Watch", "apple", "apple watch wallpaper"),
            c("ios_minimal", "iOS Minimal", "apple", "ios minimal wallpaper"),
            c("ios_gradient", "iOS Gradient", "apple", "ios gradient wallpaper", weight = 1.2f),

            // Machines
            c("machine", "Machine", "machine", "machine engineering wallpaper"),
            c("mechanical", "Mechanical", "machine", "mechanical engineering wallpaper"),
            c("engine", "Engine", "machine", "engine mechanical wallpaper"),
            c("industrial", "Industrial", "machine", "industrial machinery wallpaper"),
            c("factory", "Factory", "machine", "factory industrial wallpaper"),
            c("cnc", "CNC", "machine", "cnc machine"),
            c("heavy_machine", "Heavy Machinery", "machine", "heavy machinery wallpaper"),
            c("construction_machine", "Construction Equipment", "machine", "construction machinery wallpaper"),
            c("train", "Train", "machine", "train wallpaper"),
            c("aircraft", "Aircraft", "machine", "aircraft wallpaper"),

            // Vehicles
            c("cars", "Cars", "vehicles", "car wallpaper", weight = 1.2f),
            c("supercars", "Supercars", "vehicles", "supercar wallpaper", weight = 1.2f),
            c("sports_cars", "Sports Cars", "vehicles", "sports car wallpaper"),
            c("luxury_cars", "Luxury Cars", "vehicles", "luxury car wallpaper"),
            c("jdm", "JDM", "vehicles", "jdm car wallpaper"),
            c("muscle_car", "Muscle Cars", "vehicles", "muscle car wallpaper"),
            c("formula1", "Formula 1", "vehicles", "formula 1 wallpaper"),
            c("rally", "Rally", "vehicles", "rally car wallpaper"),
            c("bike", "Bikes", "vehicles", "motorcycle wallpaper"),
            c("superbike", "Superbikes", "vehicles", "superbike wallpaper"),
            c("truck", "Trucks", "vehicles", "truck wallpaper"),
            c("offroad", "Off-road", "vehicles", "off road vehicle wallpaper"),
            c("electric_car", "Electric Cars", "vehicles", "electric car wallpaper"),

            // Nature
            c("nature", "Nature", "nature", "nature wallpaper", weight = 1.25f),
            c("mountains", "Mountains", "nature", "mountain wallpaper"),
            c("forest", "Forest", "nature", "forest wallpaper"),
            c("river", "River", "nature", "river wallpaper"),
            c("ocean", "Ocean", "nature", "ocean wallpaper"),
            c("waterfall", "Waterfall", "nature", "waterfall wallpaper"),
            c("desert", "Desert", "nature", "desert wallpaper"),
            c("snow", "Snow", "nature", "snow landscape wallpaper"),
            c("rain", "Rain", "nature", "rain wallpaper"),
            c("sunrise", "Sunrise", "nature", "sunrise wallpaper"),
            c("sunset", "Sunset", "nature", "sunset wallpaper"),

            // Space
            c("space", "Space", "space", "space wallpaper", weight = 1.25f),
            c("galaxy", "Galaxy", "space", "galaxy wallpaper"),
            c("nebula", "Nebula", "space", "nebula wallpaper"),
            c("stars", "Stars", "space", "stars wallpaper"),
            c("planet", "Planets", "space", "planet wallpaper"),
            c("moon", "Moon", "space", "moon wallpaper"),
            c("earth", "Earth", "space", "earth from space wallpaper"),
            c("mars", "Mars", "space", "mars wallpaper"),
            c("astronaut", "Astronaut", "space", "astronaut wallpaper"),
            c("black_hole", "Black Hole", "space", "black hole wallpaper"),

            // Sky
            c("sky", "Sky", "sky", "sky wallpaper"),
            c("clouds", "Clouds", "sky", "cloud wallpaper"),
            c("night_sky", "Night Sky", "sky", "night sky wallpaper"),
            c("aurora", "Aurora", "sky", "aurora wallpaper"),
            c("lightning", "Lightning", "sky", "lightning wallpaper"),
            c("storm", "Storm", "sky", "storm wallpaper"),

            // Animals
            c("wildlife", "Wildlife", "animals", "wildlife wallpaper"),
            c("lion", "Lion", "animals", "lion wallpaper"),
            c("tiger", "Tiger", "animals", "tiger wallpaper"),
            c("wolf", "Wolf", "animals", "wolf wallpaper"),
            c("eagle", "Eagle", "animals", "eagle wallpaper"),
            c("birds", "Birds", "animals", "bird wallpaper"),
            c("cats", "Cats", "animals", "cat wallpaper"),
            c("dogs", "Dogs", "animals", "dog wallpaper"),
            c("horse", "Horse", "animals", "horse wallpaper"),
            c("marine_life", "Marine Life", "animals", "marine life wallpaper"),

            // Plants
            c("flowers", "Flowers", "plants", "flower wallpaper"),
            c("rose", "Rose", "plants", "rose wallpaper"),
            c("lotus", "Lotus", "plants", "lotus wallpaper"),
            c("sakura", "Sakura", "plants", "sakura wallpaper"),
            c("tulip", "Tulip", "plants", "tulip wallpaper"),
            c("botanical", "Botanical", "plants", "botanical wallpaper"),
            c("greenery", "Greenery", "plants", "green plants wallpaper"),

            // Architecture
            c("architecture", "Architecture", "architecture", "architecture wallpaper", weight = 1.2f),
            c("modern_architecture", "Modern Architecture", "architecture", "modern architecture wallpaper"),
            c("skyscraper", "Skyscrapers", "architecture", "skyscraper wallpaper"),
            c("futuristic_architecture", "Futuristic Buildings", "architecture", "futuristic architecture wallpaper"),
            c("interior", "Interior", "architecture", "interior design wallpaper"),
            c("bridge", "Bridges", "architecture", "bridge architecture wallpaper"),
            c("castle", "Castles", "architecture", "castle wallpaper"),
            c("ancient_architecture", "Ancient Architecture", "architecture", "ancient architecture wallpaper"),

            // Cities
            c("city", "Cities", "cities", "city wallpaper"),
            c("night_city", "Night City", "cities", "night city wallpaper"),
            c("street", "Street", "cities", "street photography wallpaper"),
            c("tokyo", "Tokyo", "cities", "tokyo wallpaper"),
            c("new_york", "New York", "cities", "new york wallpaper"),
            c("dubai", "Dubai", "cities", "dubai wallpaper"),
            c("mumbai", "Mumbai", "cities", "mumbai wallpaper"),
            c("london", "London", "cities", "london wallpaper"),
            c("paris", "Paris", "cities", "paris wallpaper"),
            c("singapore", "Singapore", "cities", "singapore wallpaper"),
            c("travel", "Travel", "cities", "travel wallpaper"),
            c("beach", "Beaches", "cities", "beach wallpaper"),
            c("island", "Islands", "cities", "island wallpaper"),

            // Art
            c("digital_art", "Digital Art", "art", "digital art wallpaper"),
            c("ai_art", "AI Art", "art", "ai art wallpaper"),
            c("illustration", "Illustration", "art", "illustration wallpaper"),
            c("abstract", "Abstract", "art", "abstract wallpaper", weight = 1.2f),
            c("three_d", "3D Art", "art", "3d art wallpaper"),
            c("concept_art", "Concept Art", "art", "concept art wallpaper"),
            c("surreal", "Surreal", "art", "surreal wallpaper"),
            c("vaporwave", "Vaporwave", "art", "vaporwave wallpaper"),
            c("synthwave", "Synthwave", "art", "synthwave wallpaper"),
            c("retro", "Retro", "art", "retro wallpaper"),
            c("vintage", "Vintage", "art", "vintage wallpaper"),

            // Minimal
            c("minimal", "Minimal", "minimal", "minimal wallpaper", weight = 1.2f),
            c("clean", "Clean", "minimal", "clean minimal wallpaper"),
            c("gradient", "Gradient", "minimal", "gradient wallpaper", weight = 1.15f),
            c("geometric", "Geometric", "minimal", "geometric wallpaper"),
            c("material", "Material", "minimal", "material design wallpaper"),
            c("pastel", "Pastel", "minimal", "pastel wallpaper"),

            // Gaming / anime
            c("gaming", "Gaming", "gaming", "gaming wallpaper"),
            c("fps", "FPS", "gaming", "fps game wallpaper"),
            c("rpg", "RPG", "gaming", "rpg game wallpaper"),
            c("racing_game", "Racing Games", "gaming", "racing game wallpaper"),
            c("battle_royale", "Battle Royale", "gaming", "battle royale wallpaper"),
            c("retro_gaming", "Retro Gaming", "gaming", "retro gaming wallpaper"),
            c("anime", "Anime", "anime", "anime wallpaper"),
            c("manga", "Manga", "anime", "manga wallpaper"),
            c("anime_scenery", "Anime Scenery", "anime", "anime scenery wallpaper"),
            c("cyber_anime", "Cyber Anime", "anime", "cyberpunk anime wallpaper"),

            // Typography / mood
            c("quotes", "Quotes", "quotes", "quote wallpaper"),
            c("motivation", "Motivational", "quotes", "motivational quote wallpaper"),
            c("typography", "Typography", "quotes", "typography wallpaper"),
            c("calligraphy", "Calligraphy", "quotes", "calligraphy wallpaper"),
            c("hindi_quotes", "Hindi Quotes", "quotes", "hindi quote wallpaper"),
            c("calm", "Calm", "mood", "calm wallpaper"),
            c("peaceful", "Peaceful", "mood", "peaceful wallpaper"),
            c("moody", "Moody", "mood", "moody wallpaper"),
            c("powerful", "Powerful", "mood", "powerful wallpaper"),
            c("aggressive", "Aggressive", "mood", "aggressive wallpaper"),
            c("chill", "Chill", "mood", "chill aesthetic wallpaper"),

            // Colors
            c("red", "Red", "color", "red wallpaper", colors = listOf("cc3333")),
            c("blue", "Blue", "color", "blue wallpaper", colors = listOf("0066cc")),
            c("green", "Green", "color", "green wallpaper", colors = listOf("00aa66")),
            c("purple", "Purple", "color", "purple wallpaper", colors = listOf("993399")),
            c("orange", "Orange", "color", "orange wallpaper"),
            c("yellow", "Yellow", "color", "yellow wallpaper"),
            c("pink", "Pink", "color", "pink wallpaper"),
            c("cyan", "Cyan", "color", "cyan wallpaper"),
            c("gold", "Gold", "color", "gold wallpaper"),
            c("silver", "Silver", "color", "silver wallpaper"),
            c("rainbow", "Rainbow", "color", "rainbow wallpaper"),
            c("colorful", "Colorful", "color", "colorful wallpaper", weight = 1.2f),

            // Trending
            c("trending", "Trending", "trending", "trending wallpaper", weight = 1.25f),
            c("popular", "Popular", "trending", "popular wallpaper"),
            c("top_rated", "Top Rated", "trending", "top rated wallpaper"),
            c("new", "New", "trending", "new wallpaper"),

            // Seasonal
            c("diwali", "Diwali", "seasonal", "diwali wallpaper"),
            c("holi", "Holi", "seasonal", "holi wallpaper"),
            c("eid", "Eid", "seasonal", "eid wallpaper"),
            c("christmas", "Christmas", "seasonal", "christmas wallpaper"),
            c("new_year", "New Year", "seasonal", "new year wallpaper"),
            c("halloween", "Halloween", "seasonal", "halloween wallpaper"),
            c("independence_day", "Independence Day", "seasonal", "india independence day wallpaper"),
            c("republic_day", "Republic Day", "seasonal", "india republic day wallpaper"),
            c("valentine", "Valentine", "seasonal", "valentine wallpaper"),

            // Professional / science / sports / food / luxury / spiritual
            c("business", "Business", "professional", "business wallpaper"),
            c("finance", "Finance", "professional", "finance wallpaper"),
            c("stocks", "Stocks", "professional", "stock market wallpaper"),
            c("workspace", "Workspace", "professional", "workspace wallpaper"),
            c("developer_desk", "Developer Desk", "professional", "developer desk setup wallpaper"),

            c("science", "Science", "science", "science wallpaper"),
            c("physics", "Physics", "science", "physics wallpaper"),
            c("chemistry", "Chemistry", "science", "chemistry wallpaper"),
            c("biology", "Biology", "science", "biology wallpaper"),
            c("astronomy", "Astronomy", "science", "astronomy wallpaper"),
            c("mathematics", "Mathematics", "science", "mathematics wallpaper"),

            c("cricket", "Cricket", "sports", "cricket wallpaper"),
            c("football", "Football", "sports", "football wallpaper"),
            c("basketball", "Basketball", "sports", "basketball wallpaper"),
            c("tennis", "Tennis", "sports", "tennis wallpaper"),
            c("motogp", "MotoGP", "sports", "motogp wallpaper"),
            c("boxing", "Boxing", "sports", "boxing wallpaper"),
            c("gym", "Gym", "sports", "gym fitness wallpaper"),

            c("food", "Food", "food", "food photography wallpaper"),
            c("coffee", "Coffee", "food", "coffee wallpaper"),
            c("tea", "Tea", "food", "tea wallpaper"),
            c("dessert", "Desserts", "food", "dessert wallpaper"),
            c("indian_food", "Indian Food", "food", "indian food photography"),

            c("luxury", "Luxury", "luxury", "luxury wallpaper"),
            c("watches", "Watches", "luxury", "luxury watch wallpaper"),
            c("mansion", "Mansions", "luxury", "luxury mansion wallpaper"),
            c("premium_interior", "Premium Interior", "luxury", "luxury interior wallpaper"),

            c("spiritual", "Spiritual", "spiritual", "spiritual wallpaper"),
            c("meditation", "Meditation", "spiritual", "meditation wallpaper"),
            c("yoga", "Yoga", "spiritual", "yoga wallpaper"),
            c("buddha", "Buddha", "spiritual", "buddha wallpaper"),
            c("shiva", "Shiva", "spiritual", "lord shiva wallpaper"),
            c("krishna", "Krishna", "spiritual", "lord krishna wallpaper"),
            c("islamic_art", "Islamic Art", "spiritual", "islamic art wallpaper"),
            c("sacred_geometry", "Sacred Geometry", "spiritual", "sacred geometry wallpaper"),
        )

    val premiumDefaultIds: Set<String> =
        setOf(
            "amoled",
            "dark_cinematic",
            "premium_colorful",
            "nature",
            "space",
            "cars",
            "technology",
            "architecture",
            "minimal",
            "abstract",
            "ios_gradient",
            "black_white",
        )

    val all: List<WallpaperCategory>
        get() = builtIn + CountryCatalog.categories

    fun byId(id: String): WallpaperCategory? =
        all.firstOrNull { it.id == id }

    fun categoriesForGroup(groupId: String): List<WallpaperCategory> =
        all.filter { it.groupId == groupId }

    fun effectiveSelection(selectedIds: Set<String>): List<WallpaperCategory> {
        val ids =
            if (selectedIds.isEmpty()) {
                premiumDefaultIds
            } else {
                selectedIds
            }

        return ids.mapNotNull(::byId)
    }
}
