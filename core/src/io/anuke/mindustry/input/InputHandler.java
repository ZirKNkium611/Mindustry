package io.anuke.mindustry.input;

import io.anuke.annotations.Annotations.Loc;
import io.anuke.annotations.Annotations.Remote;
import io.anuke.arc.Core;
import io.anuke.arc.entities.Effects;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.input.InputProcessor;
import io.anuke.arc.math.Angles;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.math.geom.Vector2;
import io.anuke.arc.scene.ui.layout.Table;
import io.anuke.arc.util.Time;
import io.anuke.mindustry.content.Blocks;
import io.anuke.mindustry.content.Fx;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.entities.effect.ItemTransfer;
import io.anuke.mindustry.entities.traits.BuilderTrait.BuildRequest;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.ValidateException;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemStack;
import io.anuke.mindustry.type.Recipe;
import io.anuke.mindustry.ui.fragments.OverlayFragment;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Build;
import io.anuke.mindustry.world.Tile;

import static io.anuke.mindustry.Vars.*;

public abstract class InputHandler implements InputProcessor{
    /**Used for dropping items.*/
    final static float playerSelectRange = mobile ? 17f : 11f;
    /**Maximum line length.*/
    final static int maxLength = 100;
    final static Vector2 stackTrns = new Vector2();
    /**Distance on the back from where items originate.*/
    final static float backTrns = 3f;

    public final Player player;
    public final OverlayFragment frag = new OverlayFragment(this);

    public Recipe recipe;
    public int rotation;
    public boolean droppingItem;

    public InputHandler(Player player){
        this.player = player;
    }

    //methods to override

    @Remote(targets = Loc.client, called = Loc.server)
    public static void dropItem(Player player, float angle){
        if(Net.server() && !player.inventory.hasItem()){
            throw new ValidateException(player, "Player cannot drop an item.");
        }

        Effects.effect(Fx.dropItem, Color.WHITE, player.x, player.y, angle, player.inventory.getItem().item);
        player.inventory.clearItem();
    }

    @Remote(targets = Loc.both, forward = true, called = Loc.server)
    public static void transferInventory(Player player, Tile tile){
        if(Net.server() && (!player.inventory.hasItem() || player.isTransferring)){
            throw new ValidateException(player, "Player cannot transfer an item.");
        }

        if(player == null || tile.entity == null) return;

        player.isTransferring = true;

        Item item = player.inventory.getItem().item;
        int amount = player.inventory.getItem().amount;
        int accepted = tile.block().acceptStack(item, amount, tile, player);
        player.inventory.getItem().amount -= accepted;

        int sent = Mathf.clamp(accepted / 4, 1, 8);
        int removed = accepted / sent;
        int[] remaining = {accepted, accepted};
        Block block = tile.block();

        for(int i = 0; i < sent; i++){
            boolean end = i == sent - 1;
            Time.run(i * 3, () -> {
                tile.block().getStackOffset(item, tile, stackTrns);

                ItemTransfer.create(item,
                        player.x + Angles.trnsx(player.rotation + 180f, backTrns), player.y + Angles.trnsy(player.rotation + 180f, backTrns),
                        new Vector2(tile.drawx() + stackTrns.x, tile.drawy() + stackTrns.y), () -> {
                            if(tile.block() != block || tile.entity == null || tile.entity.items == null) return;

                            tile.block().handleStack(item, removed, tile, player);
                            remaining[1] -= removed;

                            if(end && remaining[1] > 0){
                                tile.block().handleStack(item, remaining[1], tile, player);
                            }
                        });

                remaining[0] -= removed;

                if(end){
                    player.isTransferring = false;
                }
            });
        }
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void onTileTapped(Player player, Tile tile){
        if(tile == null || player == null) return;
        tile.block().tapped(tile, player);
    }

    public OverlayFragment getFrag(){
        return frag;
    }

    public void update(){

    }

    public float getMouseX(){
        return Core.input.mouseX();
    }

    public float getMouseY(){
        return Core.input.mouseY();
    }

    public void buildUI(Table table){

    }

    public void updateController(){

    }

    public void drawOutlined(){

    }

    public void drawTop(){

    }

    public boolean isDrawing(){
        return false;
    }

    /**Handles tile tap events that are not platform specific.*/
    boolean tileTapped(Tile tile){
        tile = tile.target();

        boolean consumed = false, showedInventory = false;

        //check if tapped block is configurable
        if(tile.block().configurable && tile.getTeam() == player.getTeam()){
            consumed = true;
            if(((!frag.config.isShown() && tile.block().shouldShowConfigure(tile, player)) //if the config fragment is hidden, show
                    //alternatively, the current selected block can 'agree' to switch config tiles
                    || (frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)))){
                frag.config.showConfig(tile);
            }
            //otherwise...
        }else if(!frag.config.hasConfigMouse()){ //make sure a configuration fragment isn't on the cursor
            //then, if it's shown and the current block 'agrees' to hide, hide it.
            if(frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)){
                consumed = true;
                frag.config.hideConfig();
            }

            if(frag.config.isShown()){
                consumed = true;
            }
        }

        //call tapped event
        if(!consumed && tile.getTeam() == player.getTeam()){
            Call.onTileTapped(player, tile);
        }

        //consume tap event if necessary
        if(tile.getTeam() == player.getTeam() && tile.block().consumesTap){
            consumed = true;
        }else if(tile.getTeam() == player.getTeam() && tile.block().synthetic() && !consumed){
            if(tile.block().hasItems && tile.entity.items.total() > 0){
                frag.inv.showFor(tile);
                consumed = true;
                showedInventory = true;
            }
        }

        if(!showedInventory){
            frag.inv.hide();
        }

        if(!consumed && player.isBuilding()){
            player.clearBuilding();
            recipe = null;
            return true;
        }

        return consumed;
    }

    /**Tries to select the player to drop off items, returns true if successful.*/
    boolean tryTapPlayer(float x, float y){
        if(canTapPlayer(x, y)){
            droppingItem = true;
            return true;
        }
        return false;
    }

    boolean canTapPlayer(float x, float y){
        return Mathf.dst(x, y, player.x, player.y) <= playerSelectRange && player.inventory.hasItem();
    }

    /**Tries to begin mining a tile, returns true if successful.*/
    boolean tryBeginMine(Tile tile){
        if(canMine(tile)){
            //if a block is clicked twice, reset it
            player.setMineTile(player.getMineTile() == tile ? null : tile);
            return true;
        }
        return false;
    }

    boolean canMine(Tile tile){
        return !Core.scene.hasMouse()
                && tile.floor().itemDrop != null && tile.floor().itemDrop.hardness <= player.mech.drillPower
                && !tile.floor().playerUnmineable
                && player.inventory.canAcceptItem(tile.floor().itemDrop)
                && tile.block() == Blocks.air && player.dst(tile.worldx(), tile.worldy()) <= Player.mineDistance;
    }

    /**Returns the tile at the specified MOUSE coordinates.*/
    Tile tileAt(float x, float y){
        return world.tile(tileX(x), tileY(y));
    }

    int tileX(float cursorX){
        Vector2 vec = Core.input.mouseWorld(cursorX, 0);
        if(selectedBlock()){
            vec.sub(recipe.result.offset(), recipe.result.offset());
        }
        return world.toTile(vec.x);
    }

    int tileY(float cursorY){
        Vector2 vec = Core.input.mouseWorld(0, cursorY);
        if(selectedBlock()){
            vec.sub(recipe.result.offset(), recipe.result.offset());
        }
        return world.toTile(vec.y);
    }

    public boolean selectedBlock(){
        return isPlacing();
    }

    public boolean isPlacing(){
        return recipe != null;
    }

    public float mouseAngle(float x, float y){
        return Core.input.mouseWorld(getMouseX(), getMouseY()).sub(x, y).angle();
    }

    public void remove(){
        Core.input.removeProcessor(this);
        frag.remove();
    }

    public boolean canShoot(){
        return recipe == null && !Core.scene.hasMouse() && !onConfigurable() && !isDroppingItem();
    }

    public boolean onConfigurable(){
        return false;
    }

    public boolean isDroppingItem(){
        return droppingItem;
    }

    public void tryDropItems(Tile tile, float x, float y){
        if(!droppingItem || !player.inventory.hasItem() || canTapPlayer(x, y)){
            droppingItem = false;
            return;
        }

        droppingItem = false;

        ItemStack stack = player.inventory.getItem();

        if(tile.block().acceptStack(stack.item, stack.amount, tile, player) > 0 && tile.getTeam() == player.getTeam() && tile.block().hasItems){
            Call.transferInventory(player, tile);
        }else{
            Call.dropItem(player.angleTo(x, y));
        }
    }

    public boolean cursorNear(){
        return true;
    }

    public void tryPlaceBlock(int x, int y){
        if(recipe != null && validPlace(x, y, recipe.result, rotation) && cursorNear()){
            placeBlock(x, y, recipe, rotation);
        }
    }

    public void tryBreakBlock(int x, int y){
        if(cursorNear() && validBreak(x, y)){
            breakBlock(x, y);
        }
    }

    public boolean validPlace(int x, int y, Block type, int rotation){
        return Build.validPlace(player.getTeam(), x, y, type, rotation) &&
        Mathf.dst(player.x, player.y, x * tilesize, y * tilesize) < Player.placeDistance;
    }

    public boolean validBreak(int x, int y){
        return Build.validBreak(player.getTeam(), x, y) && Mathf.dst(player.x, player.y, x * tilesize, y * tilesize) < Player.placeDistance;
    }

    public void placeBlock(int x, int y, Recipe recipe, int rotation){
        player.addBuildRequest(new BuildRequest(x, y, rotation, recipe));
    }

    public void breakBlock(int x, int y){
        Tile tile = world.tile(x, y).target();
        player.addBuildRequest(new BuildRequest(tile.x, tile.y));
    }

}
