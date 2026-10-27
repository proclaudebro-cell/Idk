local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local DataStoreService = game:GetService("DataStoreService")
local Workspace = game:GetService("Workspace")

local Config = require(ReplicatedStorage.Shared.Config)

local store = DataStoreService:GetDataStore(Config.DataStoreName)
local random = Random.new()

-- Map ----------------------------------------------------------------------

local function buildMap()
	local size = Config.ArenaSize

	local floor = Instance.new("Part")
	floor.Name = "Arena"
	floor.Anchored = true
	floor.Size = Vector3.new(size, 2, size)
	floor.Position = Vector3.new(0, -1, 0)
	floor.Material = Enum.Material.Grass
	floor.Color = Color3.fromRGB(88, 160, 72)
	floor.Parent = Workspace

	local spawn = Instance.new("SpawnLocation")
	spawn.Anchored = true
	spawn.Size = Vector3.new(12, 1, 12)
	spawn.Position = Vector3.new(0, 0.5, 0)
	spawn.Neutral = true
	spawn.Parent = Workspace

	-- Walls so nobody falls off the edge.
	for _, dir in { Vector3.xAxis, -Vector3.xAxis, Vector3.zAxis, -Vector3.zAxis } do
		local wall = Instance.new("Part")
		wall.Anchored = true
		wall.Transparency = 0.6
		wall.Size = Vector3.new(math.abs(dir.Z) * size + 2 * math.abs(dir.X), 30, math.abs(dir.X) * size + 2 * math.abs(dir.Z))
		wall.Position = dir * (size / 2) + Vector3.new(0, 15, 0)
		wall.Parent = Workspace
	end
end

-- Coins --------------------------------------------------------------------

local function randomArenaPosition(height: number)
	local half = Config.ArenaSize / 2 - 6
	return Vector3.new(random:NextNumber(-half, half), height, random:NextNumber(-half, half))
end

local function spawnCoin()
	local gold = random:NextNumber() < Config.GoldCoinChance

	local coin = Instance.new("Part")
	coin.Name = "Coin"
	coin.Shape = Enum.PartType.Cylinder
	coin.Size = Vector3.new(0.4, 3, 3)
	coin.CFrame = CFrame.new(randomArenaPosition(3)) * CFrame.Angles(0, 0, math.rad(90))
	coin.Anchored = true
	coin.CanCollide = false
	coin.Material = Enum.Material.Neon
	coin.Color = gold and Color3.fromRGB(255, 200, 0) or Color3.fromRGB(200, 200, 210)
	coin:SetAttribute("Value", gold and Config.GoldCoinValue or Config.CoinValue)
	coin:AddTag("Coin")
	coin.Parent = Workspace

	local collected = false
	coin.Touched:Connect(function(hit)
		if collected then
			return
		end
		local player = Players:GetPlayerFromCharacter(hit.Parent)
		if not player then
			return
		end
		collected = true
		local stats = player:FindFirstChild("leaderstats")
		if stats then
			stats.Coins.Value += coin:GetAttribute("Value")
		end
		coin:Destroy()
		task.delay(Config.CoinRespawnSeconds, spawnCoin)
	end)
end

-- Boost pads ---------------------------------------------------------------

local function spawnBoostPad(position: Vector3)
	local pad = Instance.new("Part")
	pad.Name = "BoostPad"
	pad.Anchored = true
	pad.Size = Vector3.new(8, 0.5, 8)
	pad.Position = position
	pad.Material = Enum.Material.Neon
	pad.Color = Color3.fromRGB(0, 170, 255)
	pad.Parent = Workspace

	local boosting = {}
	pad.Touched:Connect(function(hit)
		local humanoid = hit.Parent and hit.Parent:FindFirstChildOfClass("Humanoid")
		if not humanoid or boosting[humanoid] then
			return
		end
		boosting[humanoid] = true
		humanoid.WalkSpeed = Config.BoostPadSpeed
		task.delay(Config.BoostSeconds, function()
			boosting[humanoid] = nil
			if humanoid.Parent then
				humanoid.WalkSpeed = Config.DefaultSpeed
			end
		end)
	end)
end

-- Player data --------------------------------------------------------------

local function load(player: Player)
	local stats = Instance.new("Folder")
	stats.Name = "leaderstats"

	local coins = Instance.new("IntValue")
	coins.Name = "Coins"
	coins.Parent = stats

	local ok, saved = pcall(store.GetAsync, store, tostring(player.UserId))
	if ok and typeof(saved) == "number" then
		coins.Value = saved
	elseif not ok then
		warn("Failed to load data for", player.Name, saved)
	end

	stats.Parent = player
end

local function save(player: Player)
	local stats = player:FindFirstChild("leaderstats")
	if not stats then
		return
	end
	local ok, err = pcall(store.SetAsync, store, tostring(player.UserId), stats.Coins.Value)
	if not ok then
		warn("Failed to save data for", player.Name, err)
	end
end

Players.PlayerAdded:Connect(load)
Players.PlayerRemoving:Connect(save)
game:BindToClose(function()
	for _, player in Players:GetPlayers() do
		task.spawn(save, player)
	end
end)
for _, player in Players:GetPlayers() do
	task.spawn(load, player)
end

-- Start --------------------------------------------------------------------

buildMap()
for _ = 1, Config.CoinCount do
	spawnCoin()
end
for _ = 1, 4 do
	spawnBoostPad(randomArenaPosition(0.25))
end
