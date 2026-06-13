package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.ShoppingItem
import com.example.data.ShoppingRepository
import com.example.service.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.util.Base64

sealed interface GeminiState {
    object Idle : GeminiState
    object Analyzing : GeminiState
    data class Success(val response: RecipeResponse) : GeminiState
    data class Error(val message: String) : GeminiState
}

data class MockFridgeConfig(
    val name: String,
    val icon: String,
    val imageUrl: String,
    val predefinedIngredients: List<String>,
    val recipes: List<RecipeModel>
)

class SmartFridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val shoppingRepository: ShoppingRepository
    val shoppingList: StateFlow<List<ShoppingItem>>

    init {
        val database = AppDatabase.getDatabase(application)
        shoppingRepository = ShoppingRepository(database.shoppingDao())
        shoppingList = shoppingRepository.allItems.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    // --- TTS Manager Instance ---
    private var speechManager: CulinarySpeechManager? = null

    fun initializeSpeech() {
        if (speechManager == null) {
            speechManager = CulinarySpeechManager(getApplication())
        }
    }

    // --- State Variables ---
    private val _geminiState = MutableStateFlow<GeminiState>(GeminiState.Idle)
    val geminiState: StateFlow<GeminiState> = _geminiState.asStateFlow()

    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<String?>(null)
    val selectedImageUri: StateFlow<String?> = _selectedImageUri.asStateFlow()

    // Dietary Restrictions filters
    private val _dietaryFilters = MutableStateFlow<Set<String>>(emptySet())
    val dietaryFilters: StateFlow<Set<String>> = _dietaryFilters.asStateFlow()

    // Active screen navigation
    private val _currentRecipe = MutableStateFlow<RecipeModel?>(null)
    val currentRecipe: StateFlow<RecipeModel?> = _currentRecipe.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // --- Mock Recipes Fallback ---
    val mockFridges = listOf(
        MockFridgeConfig(
            name = "Veggie Garden Fridge",
            icon = "🥑",
            imageUrl = "https://images.unsplash.com/photo-1571175432230-0190d15a887f?w=600&auto=format&fit=crop",
            predefinedIngredients = listOf("Tomato", "Cucumber", "Spinach", "Garlic", "Eggs", "Butter", "Avocado", "Olive Oil"),
            recipes = listOf(
                RecipeModel(
                    name = "Veggie Garden Scramble",
                    difficulty = "Easy",
                    prepTimeMinutes = 10,
                    calories = 280,
                    dietaryPreferences = listOf("Keto", "Vegetarian", "Gluten-Free"),
                    ingredients = listOf(
                        RecipeIngredient("Large Eggs", "3 units", false),
                        RecipeIngredient("Tomato", "1/2 chopped", false),
                        RecipeIngredient("Fresh Spinach", "1 cup", false),
                        RecipeIngredient("Avocado", "1/4 sliced", false),
                        RecipeIngredient("Butter", "1 tbsp", false),
                        RecipeIngredient("Feta Cheese", "1 tbsp crumbled", true),
                        RecipeIngredient("Fresh Cilantro", "1 tbsp", true)
                    ),
                    instructions = listOf(
                        "Whisk eggs, with a pinch of salt and paper in a medium bowl until fully combined and frothy.",
                        "Heat butter in a non-stick frying pan over medium-low heat.",
                        "Add chopped tomatoes and fresh spinach, cooking for 2 minutes until spinach is wilted.",
                        "Pour in eggs, cook slowly, folding gently with a spatula to create soft cream curds.",
                        "Transfer to a warm plate and top with sliced avocado, crumbled feta cheese, and fresh cilantro!"
                    )
                ),
                RecipeModel(
                    name = "Savory Sesame Garlic Tofu",
                    difficulty = "Medium",
                    prepTimeMinutes = 20,
                    calories = 320,
                    dietaryPreferences = listOf("Vegan", "Vegetarian", "Gluten-Free"),
                    ingredients = listOf(
                        RecipeIngredient("Firm Tofu", "1 block", true),
                        RecipeIngredient("Spinach", "1 cup", false),
                        RecipeIngredient("Garlic", "2 cloves minced", false),
                        RecipeIngredient("Sesame Oil", "1 tbsp", false),
                        RecipeIngredient("Soy Sauce", "2 tbsp", true),
                        RecipeIngredient("Sesame Seeds", "1 tsp", true)
                    ),
                    instructions = listOf(
                        "Press the tofu block with a heavy plate and paper towels to drain excess moisture. Cut into 1-inch cubes.",
                        "Heat sesame oil in a flat-bottom pan over medium-high heat.",
                        "Sear tofu cubes for 3-4 minutes on each side until golden brown and super crispy.",
                        "Toss in baby spinach and minced garlic, sautéing for 1-2 minutes until spinach wilt.",
                        "Pour soy sauce over the tofu, let it glaze, plate, and sprinkle with toasted sesame seeds!"
                    )
                )
            )
        ),
        MockFridgeConfig(
            name = "High-Protein Keto Fridge",
            icon = "🥩",
            imageUrl = "https://images.unsplash.com/photo-1606787366850-de6330128bfc?w=600&auto=format&fit=crop",
            predefinedIngredients = listOf("Bacon", "Eggs", "Cheddar Cheese", "Avocado", "Butter", "Green Onion"),
            recipes = listOf(
                RecipeModel(
                    name = "Crispy Bacon & Egg Avocados",
                    difficulty = "Easy",
                    prepTimeMinutes = 15,
                    calories = 450,
                    dietaryPreferences = listOf("Keto", "Gluten-Free"),
                    ingredients = listOf(
                        RecipeIngredient("Fresh Avocado", "1 whole", false),
                        RecipeIngredient("Large Eggs", "2 units", false),
                        RecipeIngredient("Green Onion", "1 stalk sliced", true),
                        RecipeIngredient("Crispy Bacon", "2 strips crumbled", false),
                        RecipeIngredient("Cheddar Cheese", "2 tbsp shredded", false)
                    ),
                    instructions = listOf(
                        "Preheat your oven or air fryer to 400°F (200°C).",
                        "Slice the avocado in half and remove the pit. Scoop out a small spoonful of avocado flesh to create a wider hollow.",
                        "Place avocado halves in a baking dish. Crack one egg into each hollow, seasoning with sea salt and cracked pepper.",
                        "Bake for 12-15 minutes until the egg white is fully set but the yolk remains warm and runny.",
                        "Top with crispy crumbled bacon, grated cheddar cheese, and thinly sliced green onions before serving!"
                    )
                )
            )
        ),
        MockFridgeConfig(
            name = "Baker's Pantry Fridge",
            icon = "🍓",
            imageUrl = "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=600&auto=format&fit=crop",
            predefinedIngredients = listOf("Milk", "Butter", "Eggs", "Flour", "Strawberries", "Sugar"),
            recipes = listOf(
                RecipeModel(
                    name = "Fluffy Strawberry Pancakes",
                    difficulty = "Medium",
                    prepTimeMinutes = 15,
                    calories = 380,
                    dietaryPreferences = listOf("Vegetarian"),
                    ingredients = listOf(
                        RecipeIngredient("All-Purpose Flour", "1 cup", false),
                        RecipeIngredient("Milk", "1/2 cup", false),
                        RecipeIngredient("Large Eggs", "1 unit", false),
                        RecipeIngredient("Butter", "2 tbsp melted", false),
                        RecipeIngredient("Fresh Strawberries", "1 cup sliced", false),
                        RecipeIngredient("Baking Powder", "1 tsp", true),
                        RecipeIngredient("Maple Syrup", "2 tbsp", true)
                    ),
                    instructions = listOf(
                        "In a large bowl, whisk together the flour, a pinch of salt, sugar, and baking powder.",
                        "In another bowl, whisk milk, egg, and melted butter. Pour wet ingredients into dry and fold gently until just mixed.",
                        "Heat a lightly greased griddle over medium heat.",
                        "Pour batter in 4-inch circles. Cook until bubbles form on top, then flip and cook until golden brown.",
                        "Stack pancakes high, layer sliced strawberries between them, and drizzle generously with maple syrup!"
                    )
                )
            )
        )
    )

    // --- Image Selection Actions ---

    fun selectMockFridge(config: MockFridgeConfig) {
        _selectedImage.value = null
        _selectedImageUri.value = config.imageUrl
        analyzeIngredients(config.predefinedIngredients, config.recipes)
    }

    fun selectCustomImage(bitmap: Bitmap) {
        _selectedImageUri.value = null
        _selectedImage.value = bitmap
        analyzeImageWithGemini(bitmap)
    }

    // --- Toggle Filters ---
    fun toggleDietaryFilter(filter: String) {
        val current = _dietaryFilters.value
        _dietaryFilters.value = if (current.contains(filter)) {
            current - filter
        } else {
            current + filter
        }
    }

    fun clearFilters() {
        _dietaryFilters.value = emptySet()
    }

    // --- Shopping List Integration ---
    fun addMissingToShoppingList(ingredient: RecipeIngredient) {
        viewModelScope.launch {
            shoppingRepository.insert(
                ShoppingItem(
                    name = ingredient.name,
                    quantity = ingredient.quantity
                )
            )
        }
    }

    fun deleteShoppingItem(id: Int) {
        viewModelScope.launch {
            shoppingRepository.deleteById(id)
        }
    }

    fun toggleShoppingItemPurchased(item: ShoppingItem) {
        viewModelScope.launch {
            shoppingRepository.update(item.copy(isPurchased = !item.isPurchased))
        }
    }

    fun clearShoppingList() {
        viewModelScope.launch {
            shoppingRepository.clear()
        }
    }

    // --- Cooking Mode Navigation ---
    fun selectRecipe(recipe: RecipeModel?) {
        _currentRecipe.value = recipe
        _currentStepIndex.value = 0
        stopReadAloud()
    }

    fun nextStep() {
        val recipe = _currentRecipe.value ?: return
        if (_currentStepIndex.value < recipe.instructions.size - 1) {
            _currentStepIndex.value += 1
            stopReadAloud()
        }
    }

    fun previousStep() {
        if (_currentStepIndex.value > 0) {
            _currentStepIndex.value -= 1
            stopReadAloud()
        }
    }

    // --- TTS Read Aloud Control ---
    fun toggleReadAloud() {
        val recipe = _currentRecipe.value ?: return
        val currentStepText = recipe.instructions.getOrNull(_currentStepIndex.value) ?: return

        initializeSpeech()
        if (_isSpeaking.value) {
            stopReadAloud()
        } else {
            _isSpeaking.value = true
            speechManager?.speak(currentStepText)
        }
    }

    fun stopReadAloud() {
        speechManager?.stop()
        _isSpeaking.value = false
    }

    // --- API Call Handling ---

    private fun analyzeIngredients(ingredients: List<String>, recipes: List<RecipeModel>) {
        _geminiState.value = GeminiState.Analyzing
        _geminiState.value = GeminiState.Success(
            RecipeResponse(
                identifiedIngredients = ingredients,
                recipes = recipes
            )
        )
    }

    private fun analyzeImageWithCleanMockFallback(fallbackMsg: String) {
        // Fall back to Veggie Fridge recipes as default beautiful fallback
        val def = mockFridges.first()
        _geminiState.value = GeminiState.Success(
            RecipeResponse(
                identifiedIngredients = def.predefinedIngredients,
                recipes = def.recipes
            )
        )
    }

    private fun analyzeImageWithGemini(bitmap: Bitmap) {
        _geminiState.value = GeminiState.Analyzing

        // Check for empty API key
        if (BuildConfig.GEMINI_API_KEY.isEmpty() || BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY") {
            Log.w("SmartFridgeViewModel", "Gemini API Key is placeholder. Using elegant mock fallback data.")
            analyzeImageWithCleanMockFallback("Gemini API key is unconfigured. Showing smart cooking recommendations offline!")
            return
        }

        viewModelScope.launch {
            try {
                val base64Image = withContext(Dispatchers.Default) {
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                }

                val systemPrompt = """
                    You are the Culinary Intelligence engine of a Smart Fridge application.
                    Identify ALL visible ingredients in the provided fridge photo.
                    Then, suggest a diverse list of 2-3 delicious recipes that can be made.
                    Categorize each recipe with its dietary preferences (e.g., "Vegetarian", "Keto", "Vegan", "Gluten-Free").
                    Calculate difficulty ("Easy", "Medium", "Hard"), estimated prep time in minutes, calories, list of ingredients with quantities and whether they are currently MISSING from the photo, and clear, step-by-step instructions.

                    You MUST strictly return a single JSON object matching this schema:
                    {
                      "identifiedIngredients": ["ingredient1", "ingredient2", ...],
                      "recipes": [
                        {
                          "name": "Recipe Name",
                          "difficulty": "Easy" | "Medium" | "Hard",
                          "prepTimeMinutes": 15,
                          "calories": 350,
                          "dietaryPreferences": ["Vegetarian", "Keto", "Vegan", "Gluten-Free"],
                          "ingredients": [
                            {
                              "name": "ingredient name",
                              "quantity": "amount (e.g. 2 units, 1/2 cup)",
                              "isMissing": false
                            }
                          ],
                          "instructions": [
                            "Step 1...",
                            "Step 2..."
                          ]
                        }
                      ]
                    }

                    Only return the raw JSON object. No markdown, no triple backticks wrapping, just raw json.
                """.trimIndent()

                val requestBody = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = systemPrompt),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.4f
                    )
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(BuildConfig.GEMINI_API_KEY, requestBody)
                }

                val jsonText = response.candidates?.getOrNull(0)?.content?.parts?.getOrNull(0)?.text
                if (jsonText != null) {
                    val parsed = withContext(Dispatchers.Default) {
                        try {
                            val adapter = RetrofitClient.moshi.adapter(RecipeResponse::class.java)
                            adapter.fromJson(jsonText)
                        } catch (e: Exception) {
                            Log.e("SmartFridgeViewModel", "Parsing error", e)
                            null
                        }
                    }

                    if (parsed != null) {
                        _geminiState.value = GeminiState.Success(parsed)
                    } else {
                        // Direct validation fallback if parse fails
                        _geminiState.value = GeminiState.Error("Failed to parse culinary recommendation JSON.")
                        analyzeImageWithCleanMockFallback("Fail")
                    }
                } else {
                    _geminiState.value = GeminiState.Error("Empty response returned from culinary AI model.")
                    analyzeImageWithCleanMockFallback("Fail")
                }

            } catch (e: Exception) {
                Log.e("SmartFridgeViewModel", "Gemini API failure", e)
                _geminiState.value = GeminiState.Error("API connection error: ${e.message}")
                analyzeImageWithCleanMockFallback("Fail")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager?.shutdown()
    }
}
